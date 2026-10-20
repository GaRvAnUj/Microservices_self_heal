package com.selfheal.healer.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DiagnosisClientTest {

    @Test
    void testGracefulFallbackWhenPythonServiceUnavailable() {
        StatisticalLocalizer localizer = new StatisticalLocalizer();
        // Point to an inactive port to simulate service unreachable
        DiagnosisClient client = new DiagnosisClient(localizer, "http://127.0.0.1:59999/diagnose");

        Map<String, Double> zScores = Map.of(
                "link:compose-post->text:avg_latency", 6.2,
                "node:text:cpu_process", 0.5
        );

        DiagnosisClient.DiagnosisResult result = client.diagnose(zScores, Map.of());

        assertNotNull(result);
        assertTrue(result.anomalyDetected());
        assertEquals("compose-post->text", result.targetEntity());
        assertEquals("link_delay", result.faultType());
        assertTrue(result.isLinkFault());
        // Verify scientific integrity tag
        assertEquals("JAVA_STATISTICAL_APPROXIMATION", result.diagnosisSource());
    }
}
