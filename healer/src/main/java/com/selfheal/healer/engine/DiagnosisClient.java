package com.selfheal.healer.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * Diagnosis client attempting FastAPI call (http://127.0.0.1:5000/diagnose) with 1s timeout,
 * with graceful fallback to the built-in Java statistical localizer.
 *
 * NOTE: When falling back to Java localizer, diagnosisSource is explicitly marked
 * as JAVA_STATISTICAL_APPROXIMATION to maintain scientific integrity.
 */
@Component
public class DiagnosisClient {
    private static final Logger log = Logger.getLogger(DiagnosisClient.class.getName());

    public enum SourceMode {
        AUTO,
        MODIFY,
        FALLBACK
    }

    public record DiagnosisResult(
            boolean anomalyDetected,
            String targetEntity,       // e.g. "text" or "compose-post->text"
            String faultType,          // cpu_hog, crash, link_delay, link_loss
            boolean isLinkFault,
            double confidence,
            String diagnosisSource     // PYTHON_FASTAPI or JAVA_STATISTICAL_APPROXIMATION
    ) {}

    private final StatisticalLocalizer statisticalLocalizer;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String pythonEndpoint;
    private volatile SourceMode sourceMode = SourceMode.AUTO;
    private final LongAdder diagnosisCalls = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder fallbacks = new LongAdder();
    private final ConcurrentLinkedDeque<Long> latencySamplesMs = new ConcurrentLinkedDeque<>();

    @Autowired
    public DiagnosisClient(StatisticalLocalizer statisticalLocalizer) {
        this(statisticalLocalizer, "http://127.0.0.1:5000/diagnose");
    }

    public DiagnosisClient(StatisticalLocalizer statisticalLocalizer, String pythonEndpoint) {
        this.statisticalLocalizer = statisticalLocalizer;
        this.pythonEndpoint = pythonEndpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1000))
                .build();
        this.mapper = new ObjectMapper();
    }

    public DiagnosisResult diagnose(Map<String, Double> zScores, Map<String, Double> smoothedSignals) {
        return diagnose(zScores, smoothedSignals, true);
    }

    public DiagnosisResult diagnose(Map<String, Double> zScores,
                                    Map<String, Double> smoothedSignals,
                                    boolean allowLinkCandidates) {
        return diagnose(zScores, smoothedSignals, allowLinkCandidates, Set.of());
    }

    public DiagnosisResult diagnose(Map<String, Double> zScores,
                                    Map<String, Double> smoothedSignals,
                                    boolean allowLinkCandidates,
                                    Set<String> excludedTargets) {
        Set<String> excluded = excludedTargets == null ? Set.of() : excludedTargets;
        long startedAt = System.nanoTime();
        diagnosisCalls.increment();
        DiagnosisResult result = null;
        // 1. Try external Python FastAPI endpoint unless Java-only diagnosis was requested.
        if (sourceMode != SourceMode.FALLBACK) {
            try {
                String requestJson = mapper.writeValueAsString(Map.of(
                        "zScores", zScores,
                        "signals", smoothedSignals,
                        "allowLinkDiagnosis", allowLinkCandidates
                ));

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(pythonEndpoint))
                        .timeout(Duration.ofMillis(1000))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                    JsonNode root = mapper.readTree(resp.body());
                    boolean anomaly = root.path("anomalyDetected").asBoolean(true);
                    String target = textValueOrNull(root.path("targetEntity"));
                    String fault = textValueOrNull(root.path("faultType"));
                    boolean isLink = target != null && target.contains("->");
                    double conf = root.path("confidence").asDouble(0.95);

                    String source = root.path("diagnosisSource").asText("PYTHON_FASTAPI");
                    boolean modifySourceRequired = sourceMode != SourceMode.MODIFY
                            || "MODIFy".equalsIgnoreCase(source);
                    if (anomaly && Incident.isValidTarget(target) && fault != null
                            && !excluded.contains(target) && modifySourceRequired) {
                        result = new DiagnosisResult(anomaly, target, fault, isLink, conf, source);
                    } else {
                        log.warning("Diagnosis service returned no usable candidate or repeated an excluded candidate; using Java statistical fallback.");
                    }
                } else {
                    log.warning("Diagnosis service returned HTTP " + resp.statusCode() + "; using Java statistical fallback.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warning("Diagnosis request interrupted; using Java statistical fallback.");
            } catch (Exception e) {
                if (e instanceof HttpTimeoutException) {
                    timeouts.increment();
                }
                log.warning("Diagnosis service request failed; using Java statistical fallback: " + e.getMessage());
            }
        }

        if (result == null) {
            fallbacks.increment();
            result = statisticalLocalizer.localize(zScores, smoothedSignals, allowLinkCandidates, excluded);
            if (!allowLinkCandidates) {
                String inferredLinkFault = statisticalLocalizer.inferLinkFaultType(zScores);
                if (inferredLinkFault != null) {
                    result = new DiagnosisResult(
                            result.anomalyDetected(),
                            result.targetEntity(),
                            inferredLinkFault,
                            false,
                            result.confidence(),
                            result.diagnosisSource()
                    );
                }
            }
        } else if (!"MODIFy".equalsIgnoreCase(result.diagnosisSource())) {
            fallbacks.increment();
        }

        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        latencySamplesMs.addLast(elapsedMs);
        while (latencySamplesMs.size() > 10000) {
            latencySamplesMs.pollFirst();
        }
        return result;
    }

    public void setSourceMode(String sourceMode) {
        if (sourceMode == null) {
            throw new IllegalArgumentException("Diagnosis source must be auto, modify, or fallback.");
        }
        try {
            this.sourceMode = SourceMode.valueOf(sourceMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Diagnosis source must be auto, modify, or fallback.", e);
        }
    }

    public String getSourceMode() {
        return sourceMode.name().toLowerCase();
    }

    private static String textValueOrNull(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        return Incident.isValidTarget(text) ? text : null;
    }

    public Map<String, Object> getLatencyMetrics() {
        List<Long> samples = new ArrayList<>(latencySamplesMs);
        Collections.sort(samples);
        double mean = samples.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double p95 = samples.isEmpty() ? 0.0 : samples.get((int) Math.ceil(samples.size() * 0.95) - 1);
        return Map.of(
                "n_calls", diagnosisCalls.sum(),
                "mean_ms", mean,
                "p95_ms", p95,
                "timeouts", timeouts.sum(),
                "fallback_count", fallbacks.sum()
        );
    }
}
