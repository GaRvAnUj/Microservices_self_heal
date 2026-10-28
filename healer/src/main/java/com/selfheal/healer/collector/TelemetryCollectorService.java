package com.selfheal.healer.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfheal.healer.service.ServiceManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Polls GET /internal/telemetry across all microservices every 3s.
 * Maintains rolling 3-window sample means and triggers baseline training.
 */
@Service
public class TelemetryCollectorService {
    private static final Logger log = Logger.getLogger(TelemetryCollectorService.class.getName());
    public static final int BASELINE_WARMUP_REQUIRED_SAMPLES = 20;
    public static final long TELEMETRY_POLL_INTERVAL_MS = 3000;

    private final ServiceManager serviceManager;
    private final BaselineProfileManager baselineManager;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    private final Deque<Map<String, Double>> rollingWindows = new ConcurrentLinkedDeque<>();
    private final List<Map<String, Double>> baselineTrainingBuffer = new CopyOnWriteArrayList<>();

    private volatile Map<String, Double> latestSmoothedSample = new ConcurrentHashMap<>();
    private volatile Map<String, Double> latestZScores = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private int baselineWarmupRequiredSamples = BASELINE_WARMUP_REQUIRED_SAMPLES;
    private volatile long collectorStartedAt = 0L;

    public TelemetryCollectorService(ServiceManager serviceManager,
                                     BaselineProfileManager baselineManager) {
        this.serviceManager = serviceManager;
        this.baselineManager = baselineManager;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(800))
                .build();
        this.mapper = new ObjectMapper();
    }

    @PostConstruct
    public synchronized void start() {
        if (scheduler == null || scheduler.isShutdown()) {
            collectorStartedAt = System.currentTimeMillis();
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "telemetry-collector");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(
                    this::collectCycle, 1, TELEMETRY_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS
            );
            log.info("TelemetryCollectorService scheduled (poll every 3s, 3-window sample smoothing).");
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public void collectCycle() {
        try {
            Map<String, Double> rawSnapshot = new LinkedHashMap<>();

            for (ServiceManager.ServiceDef def : serviceManager.getServiceDefs()) {
                pollServiceTelemetry(def, rawSnapshot);
            }

            if (rawSnapshot.isEmpty()) {
                return;
            }

            // 1. Maintain rolling 3-window buffer
            rollingWindows.addLast(rawSnapshot);
            while (rollingWindows.size() > 3) {
                rollingWindows.pollFirst();
            }

            // 2. Compute Smoothed Sample = mean over available windows (up to 3)
            Map<String, Double> smoothed = computeSmoothedSample(new ArrayList<>(rollingWindows));
            this.latestSmoothedSample = Collections.unmodifiableMap(smoothed);

            // 3. Baseline training vs z-score computation
            if (!baselineManager.isBaselineEstablished()) {
                baselineTrainingBuffer.add(smoothed);
                if (baselineTrainingBuffer.size() >= baselineWarmupRequiredSamples) {
                    log.info("Establishing baseline profile with " + baselineTrainingBuffer.size() + " samples...");
                    baselineManager.trainBaseline(new ArrayList<>(baselineTrainingBuffer));
                    baselineTrainingBuffer.clear();
                    log.info("Baseline profile established successfully.");
                }
            } else {
                Map<String, Double> zScores = baselineManager.computeAllZScores(smoothed);
                this.latestZScores = Collections.unmodifiableMap(zScores);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Error during telemetry collection cycle: " + e.getMessage());
        }
    }

    private void pollServiceTelemetry(ServiceManager.ServiceDef def, Map<String, Double> outSignals) {
        String url = "http://127.0.0.1:" + def.port() + "/internal/telemetry";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(800))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && resp.body() != null) {
                JsonNode root = mapper.readTree(resp.body());

                // Node metrics
                JsonNode nodeMetrics = root.path("nodeMetrics");
                if (!nodeMetrics.isMissingNode()) {
                    outSignals.put("node:" + def.name() + ":cpu_process", nodeMetrics.path("processCpuLoad").asDouble(0.0));
                    outSignals.put("node:" + def.name() + ":cpu_system", nodeMetrics.path("systemCpuLoad").asDouble(0.0));
                    outSignals.put("node:" + def.name() + ":heap_used", (double) nodeMetrics.path("heapUsedBytes").asLong(0));
                    outSignals.put("node:" + def.name() + ":heap_committed", (double) nodeMetrics.path("heapCommittedBytes").asLong(0));
                    outSignals.put("node:" + def.name() + ":request_count", (double) nodeMetrics.path("requestCount").asLong(0));
                }

                // Edge stats
                JsonNode edgeStats = root.path("edgeStats");
                if (edgeStats.isArray()) {
                    for (JsonNode edge : edgeStats) {
                        String caller = edge.path("caller").asText(def.name());
                        String target = edge.path("target").asText();
                        String edgePrefix = "link:" + caller + "->" + target;
                        outSignals.put(edgePrefix + ":avg_latency", edge.path("avgLatencyMs").asDouble(0.0));
                        outSignals.put(edgePrefix + ":error_rate", edge.path("errorRate").asDouble(0.0));
                        outSignals.put(edgePrefix + ":call_count", (double) edge.path("callCount").asLong(0));
                    }
                }

                // Log event counters
                JsonNode logEvents = root.path("logEventCounters");
                if (!logEvents.isMissingNode()) {
                    outSignals.put("log:" + def.name() + ":LINK_TIMEOUT", (double) logEvents.path("LINK_TIMEOUT").asLong(0));
                    outSignals.put("log:" + def.name() + ":DOWNSTREAM_ERROR", (double) logEvents.path("DOWNSTREAM_ERROR").asLong(0));
                }
            }
        } catch (Exception ignored) {
            // Service might be down or restarting
        }
    }

    public static Map<String, Double> computeSmoothedSample(List<Map<String, Double>> windows) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (windows == null || windows.isEmpty()) return result;

        Map<String, Double> sumMap = new HashMap<>();
        Map<String, Integer> countMap = new HashMap<>();

        for (Map<String, Double> win : windows) {
            for (Map.Entry<String, Double> entry : win.entrySet()) {
                sumMap.merge(entry.getKey(), entry.getValue(), Double::sum);
                countMap.merge(entry.getKey(), 1, Integer::sum);
            }
        }

        for (Map.Entry<String, Double> entry : sumMap.entrySet()) {
            int count = countMap.getOrDefault(entry.getKey(), 1);
            result.put(entry.getKey(), entry.getValue() / count);
        }

        return result;
    }

    public Map<String, Double> getLatestSmoothedSample() {
        return latestSmoothedSample;
    }

    public boolean isBaselineEstablished() {
        return baselineManager.isBaselineEstablished();
    }

    public int getBaselineSignalCount() {
        return baselineManager.getAllBaselines().size();
    }

    public long getBaselineProfileDurationMs() {
        long establishedAt = baselineManager.getBaselineEstablishedAt();
        return collectorStartedAt > 0 && establishedAt >= collectorStartedAt
                ? establishedAt - collectorStartedAt
                : -1L;
    }

    public Map<String, Double> getLatestZScores() {
        return latestZScores;
    }

    public int getBaselineWarmupRequiredSamples() {
        return baselineWarmupRequiredSamples;
    }

    public void setBaselineWarmupRequiredSamples(int samples) {
        this.baselineWarmupRequiredSamples = Math.max(1, samples);
    }
}
