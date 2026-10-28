package com.selfheal.healer.load;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generates continuous synthetic traffic (5-10 req/s) to the gateway and tracks SLO metrics.
 * SLO Healthy: p95 < 400 ms AND error rate < 3% (0.03).
 */
@Component
public class LoadGenerator {
    private static final Logger log = Logger.getLogger(LoadGenerator.class.getName());
    public static final long WINDOW_MS = 10000;
    public static final double SLO_P95_LIMIT_MS = 400.0;
    public static final double SLO_ERROR_LIMIT = 0.03;

    public record RequestRecord(long timestamp, long latencyMs, boolean success) {}

    public record SloStatus(double p95LatencyMs, double avgLatencyMs, double errorRate,
                            long windowRequests, boolean sloHealthy, long totalRequests) {}

    private final String gatewayUrl;
    private final HttpClient httpClient;
    private final ConcurrentLinkedDeque<RequestRecord> slidingWindow = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledExecutorService executorService;
    private int targetRps = 7; // 7 requests/sec (within 5-10 range)

    public LoadGenerator() {
        this("http://127.0.0.1:8081/work");
    }

    public LoadGenerator(String gatewayUrl) {
        this.gatewayUrl = gatewayUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(800))
                .build();
    }

    @PostConstruct
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            executorService = Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "load-gen-worker");
                t.setDaemon(true);
                return t;
            });

            // Schedule requests at targetRps
            long periodMicros = 1_000_000L / Math.max(1, targetRps);
            executorService.scheduleAtFixedRate(this::sendSingleRequest, 0, periodMicros, TimeUnit.MICROSECONDS);
            log.info("LoadGenerator started targeting " + targetRps + " req/s at " + gatewayUrl);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            if (executorService != null) {
                executorService.shutdownNow();
                executorService = null;
            }
            log.info("LoadGenerator stopped.");
        }
    }

    void sendSingleRequest() {
        if (!running.get()) return;

        totalRequests.incrementAndGet();
        long start = System.nanoTime();
        boolean success = false;
        long elapsedMs = 0;

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(gatewayUrl))
                    .timeout(Duration.ofMillis(1500))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            success = resp.statusCode() == 200;
        } catch (Exception e) {
            elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            success = false;
        }

        recordResult(System.currentTimeMillis(), elapsedMs, success);
    }

    public void recordResult(long timestamp, long latencyMs, boolean success) {
        slidingWindow.addLast(new RequestRecord(timestamp, latencyMs, success));
        pruneWindow(timestamp);
    }

    private void pruneWindow(long now) {
        long cutoff = now - WINDOW_MS;
        while (!slidingWindow.isEmpty()) {
            RequestRecord first = slidingWindow.peekFirst();
            if (first != null && first.timestamp() < cutoff) {
                slidingWindow.pollFirst();
            } else {
                break;
            }
        }
    }

    public SloStatus getSloStatus() {
        long now = System.currentTimeMillis();
        pruneWindow(now);

        List<RequestRecord> snapshot = new ArrayList<>(slidingWindow);
        if (snapshot.isEmpty()) {
            return new SloStatus(0.0, 0.0, 0.0, 0, true, totalRequests.get());
        }

        List<Long> latencies = new ArrayList<>(snapshot.size());
        long errCount = 0;
        long sumLat = 0;

        for (RequestRecord r : snapshot) {
            latencies.add(r.latencyMs());
            sumLat += r.latencyMs();
            if (!r.success()) {
                errCount++;
            }
        }

        Collections.sort(latencies);
        int p95Index = (int) Math.ceil(0.95 * latencies.size()) - 1;
        p95Index = Math.max(0, Math.min(p95Index, latencies.size() - 1));
        double p95 = latencies.get(p95Index);
        double avg = (double) sumLat / latencies.size();
        double errorRate = (double) errCount / latencies.size();

        // SLO: p95 < 400 ms AND errorRate < 3% (0.03)
        boolean healthy = (p95 < SLO_P95_LIMIT_MS) && (errorRate < SLO_ERROR_LIMIT);

        return new SloStatus(p95, avg, errorRate, snapshot.size(), healthy, totalRequests.get());
    }

    public boolean isSloHealthy() {
        return getSloStatus().sloHealthy();
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getTargetRps() {
        return targetRps;
    }

    public void setTargetRps(int targetRps) {
        this.targetRps = Math.max(1, targetRps);
    }
}
