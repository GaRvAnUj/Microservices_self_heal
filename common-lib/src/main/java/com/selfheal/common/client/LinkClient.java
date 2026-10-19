package com.selfheal.common.client;

import com.selfheal.common.chaos.ChaosRegistryCache;
import com.selfheal.common.model.EdgePolicy;
import com.selfheal.common.model.EdgeStats;
import com.selfheal.common.model.LinkFault;
import com.selfheal.common.policy.EdgePolicyRegistry;
import com.selfheal.common.telemetry.LogEventTracker;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resilient HTTP client managing per-link policies, chaos simulation, retries, and telemetry.
 */
public class LinkClient {
    private final String serviceName;
    private final EdgePolicyRegistry policyRegistry;
    private final ChaosRegistryCache chaosCache;
    private final LogEventTracker logTracker;
    private final HttpClient httpClient;

    private final Map<String, EdgeStats> edgeStatsMap = new ConcurrentHashMap<>();
    private final AtomicLong totalCallsCounter = new AtomicLong(0);
    private final AtomicLong totalRetriesCounter = new AtomicLong(0);

    public LinkClient(String serviceName,
                      EdgePolicyRegistry policyRegistry,
                      ChaosRegistryCache chaosCache,
                      LogEventTracker logTracker) {
        this.serviceName = serviceName;
        this.policyRegistry = policyRegistry;
        this.chaosCache = chaosCache;
        this.logTracker = logTracker;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(800))
                .build();
    }

    public String call(String targetService, String url, String method, String requestBody) throws IOException, InterruptedException {
        EdgePolicy policy = policyRegistry.getPolicy(targetService);
        EdgeStats stats = getOrCreateStats(targetService);
        totalCallsCounter.incrementAndGet();

        // 1. Circuit Breaker Check
        if (policy.isBreakerOpen()) {
            logTracker.increment(LogEventTracker.DOWNSTREAM_ERROR);
            stats.recordCall(0, true, 0, 0);
            throw new CircuitBreakerOpenException("Circuit breaker open for outgoing link to " + targetService);
        }

        // 2. Query Chaos Registry for injected link delay / loss
        LinkFault fault = chaosCache != null ? chaosCache.getLinkFault(serviceName, targetService) : null;
        int delayApplied = 0;
        if (fault != null) {
            if (fault.getDelayMs() > 0) {
                delayApplied = fault.getDelayMs();
                Thread.sleep(delayApplied);
            }
            if (fault.getLossPct() > 0) {
                double roll = ThreadLocalRandom.current().nextDouble(100.0);
                if (roll < fault.getLossPct()) {
                    logTracker.increment(LogEventTracker.DOWNSTREAM_ERROR);
                    stats.recordCall(delayApplied, true, 0, 0);
                    throw new IOException("Simulated network packet drop on link " + serviceName + "->" + targetService);
                }
            }
        }

        // 3. HTTP Call with policy timeout and retry budget
        int maxAttempts = 1 + policy.getMaxRetries();
        int attempts = 0;
        long callStartNanos = System.nanoTime();
        Exception lastException = null;

        while (attempts < maxAttempts) {
            attempts++;
            long attemptStartNanos = System.nanoTime();
            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(policy.getTimeoutMs()));

                if ("POST".equalsIgnoreCase(method)) {
                    reqBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody != null ? requestBody : ""))
                              .header("Content-Type", "application/json");
                } else {
                    reqBuilder.GET();
                }

                HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                long elapsedMs = delayApplied + Duration.ofNanos(System.nanoTime() - callStartNanos).toMillis();

                if (response.statusCode() >= 200 && response.statusCode() < 400) {
                    long bytesIn = response.body() != null ? response.body().getBytes().length : 0;
                    long bytesOut = requestBody != null ? requestBody.getBytes().length : 0;
                    stats.recordCall(elapsedMs, false, bytesIn, bytesOut);
                    return response.body();
                } else {
                    throw new IOException("Downstream HTTP error " + response.statusCode() + " from " + targetService);
                }
            } catch (Exception e) {
                lastException = e;
                if (e instanceof HttpTimeoutException || e instanceof HttpConnectTimeoutException) {
                    logTracker.increment(LogEventTracker.LINK_TIMEOUT);
                } else {
                    logTracker.increment(LogEventTracker.DOWNSTREAM_ERROR);
                }

                // Check if allowed to retry within retry budget
                if (attempts < maxAttempts && canUseRetryBudget(policy.getRetryBudgetPct())) {
                    totalRetriesCounter.incrementAndGet();
                    continue; // execute next retry
                }
                break;
            }
        }

        long totalElapsedMs = delayApplied + Duration.ofNanos(System.nanoTime() - callStartNanos).toMillis();
        stats.recordCall(totalElapsedMs, true, 0, 0);

        if (lastException instanceof IOException ioException) {
            throw ioException;
        } else if (lastException instanceof InterruptedException interruptedException) {
            throw interruptedException;
        } else {
            throw new IOException("Failed link call to " + targetService + ": " + (lastException != null ? lastException.getMessage() : "unknown error"), lastException);
        }
    }

    private boolean canUseRetryBudget(int retryBudgetPct) {
        long calls = totalCallsCounter.get();
        long retries = totalRetriesCounter.get() + 1;
        if (calls <= 5) {
            return true; // allow initial warmup retries
        }
        return ((double) retries / calls) * 100.0 <= retryBudgetPct;
    }

    public EdgeStats getOrCreateStats(String targetService) {
        return edgeStatsMap.computeIfAbsent(targetService, k -> new EdgeStats(serviceName, k));
    }

    public List<EdgeStats.Snapshot> getEdgeStatsSnapshots() {
        List<EdgeStats.Snapshot> snapshots = new ArrayList<>();
        for (EdgeStats stats : edgeStatsMap.values()) {
            snapshots.add(stats.snapshot());
        }
        return snapshots;
    }

    public String getServiceName() {
        return serviceName;
    }
}
