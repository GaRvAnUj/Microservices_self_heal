package com.selfheal.healer.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class StatisticalLocalizerTest {

    private final StatisticalLocalizer localizer = new StatisticalLocalizer();

    @Test
    void testPureLinkFaultDisambiguation() {
        // Link (compose-post -> text) latency is high (z = 6.5)
        // Callee text internal CPU is normal (z = 0.5)
        Map<String, Double> zScores = Map.of(
                "link:compose-post->text:avg_latency", 6.5,
                "node:text:cpu_process", 0.5,
                "node:compose-post:cpu_process", 0.8
        );

        DiagnosisClient.DiagnosisResult result = localizer.localize(zScores, Map.of());

        assertTrue(result.anomalyDetected());
        assertEquals("compose-post->text", result.targetEntity());
        assertEquals("link_delay", result.faultType());
        assertTrue(result.isLinkFault(), "N1 Disambiguation must classify as link fault when callee is healthy");
        assertEquals("JAVA_STATISTICAL_APPROXIMATION", result.diagnosisSource());
    }

    @Test
    void testNodeFaultMaskedAsLinkDisambiguation() {
        // Link latency is high (z = 6.0), BUT callee text CPU is ALSO high (z = 7.5)
        Map<String, Double> zScores = Map.of(
                "link:compose-post->text:avg_latency", 6.0,
                "node:text:cpu_process", 7.5,
                "node:compose-post:cpu_process", 0.5
        );

        DiagnosisClient.DiagnosisResult result = localizer.localize(zScores, Map.of());

        assertTrue(result.anomalyDetected());
        assertEquals("text", result.targetEntity(), "N1 Disambiguation must blame node when callee itself is abnormal");
        assertEquals("cpu_hog", result.faultType());
        assertFalse(result.isLinkFault());
    }

    @Test
    void testLinkLossDisambiguation() {
        // Link error rate is high (z = 5.2), latency normal, callee normal
        Map<String, Double> zScores = Map.of(
                "link:compose-post->media:error_rate", 5.2,
                "link:compose-post->media:avg_latency", 0.2,
                "node:media:cpu_process", 0.1
        );

        DiagnosisClient.DiagnosisResult result = localizer.localize(zScores, Map.of());

        assertTrue(result.anomalyDetected());
        assertEquals("compose-post->media", result.targetEntity());
        assertEquals("link_loss", result.faultType());
        assertTrue(result.isLinkFault());
    }

    @Test
    void testDirectNodeCrash() {
        Map<String, Double> zScores = Map.of(
                "log:post-storage:DOWNSTREAM_ERROR", 6.8,
                "node:gateway:cpu_process", 0.2
        );

        DiagnosisClient.DiagnosisResult result = localizer.localize(zScores, Map.of());

        assertTrue(result.anomalyDetected());
        assertEquals("post-storage", result.targetEntity());
        assertEquals("crash", result.faultType());
        assertFalse(result.isLinkFault());
    }
}
