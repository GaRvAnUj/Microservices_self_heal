package com.selfheal.healer.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
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
        // 1. Try external Python FastAPI endpoint first
        try {
            String requestJson = mapper.writeValueAsString(Map.of(
                    "zScores", zScores,
                    "signals", smoothedSignals
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
                String target = root.path("targetEntity").asText();
                String fault = root.path("faultType").asText();
                boolean isLink = root.path("isLinkFault").asBoolean(target != null && target.contains("->"));
                double conf = root.path("confidence").asDouble(0.95);

                return new DiagnosisResult(anomaly, target, fault, isLink, conf, "PYTHON_FASTAPI");
            }
        } catch (Exception ignored) {
            // Python service not running or timed out; fallback to statistical localizer
        }

        // 2. Fallback to Java statistical localizer
        return statisticalLocalizer.localize(zScores, smoothedSignals);
    }
}
