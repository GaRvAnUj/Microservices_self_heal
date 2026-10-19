package com.selfheal.healer.collector;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class BaselineProfileManagerTest {

    @Test
    void testTrainBaselineAndComputeZScore() {
        BaselineProfileManager manager = new BaselineProfileManager();
        assertFalse(manager.isBaselineEstablished());

        // 10 samples with cpu_process values around 0.10
        List<Map<String, Double>> samples = List.of(
                Map.of("node:gateway:cpu_process", 0.10, "link:gateway->compose-post:avg_latency", 20.0),
                Map.of("node:gateway:cpu_process", 0.12, "link:gateway->compose-post:avg_latency", 22.0),
                Map.of("node:gateway:cpu_process", 0.08, "link:gateway->compose-post:avg_latency", 18.0),
                Map.of("node:gateway:cpu_process", 0.10, "link:gateway->compose-post:avg_latency", 20.0)
        );

        manager.trainBaseline(samples);
        assertTrue(manager.isBaselineEstablished());

        // Normal value should have z-score close to 0
        double zNormal = manager.computeZScore("node:gateway:cpu_process", 0.10);
        assertEquals(0.0, zNormal, 0.2);

        // Huge spike should produce high z-score (z > 4)
        double zSpike = manager.computeZScore("node:gateway:cpu_process", 0.95);
        assertTrue(zSpike > 4.0, "Expected z-score > 4 for severe CPU hog anomaly, got: " + zSpike);

        // Link latency spike
        double zLinkDelay = manager.computeZScore("link:gateway->compose-post:avg_latency", 250.0);
        assertTrue(zLinkDelay > 4.0, "Expected z-score > 4 for link latency spike, got: " + zLinkDelay);
    }
}
