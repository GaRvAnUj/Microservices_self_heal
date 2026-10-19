package com.selfheal.healer.load;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class LoadGeneratorTest {

    @Test
    void testHealthySloCalculation() {
        LoadGenerator generator = new LoadGenerator();
        long now = System.currentTimeMillis();

        // 100 successful requests with 20ms latency
        for (int i = 0; i < 100; i++) {
            generator.recordResult(now, 20, true);
        }

        LoadGenerator.SloStatus status = generator.getSloStatus();
        assertEquals(20.0, status.p95LatencyMs());
        assertEquals(20.0, status.avgLatencyMs());
        assertEquals(0.0, status.errorRate());
        assertTrue(status.sloHealthy());
    }

    @Test
    void testViolatedSloDueToLatency() {
        LoadGenerator generator = new LoadGenerator();
        long now = System.currentTimeMillis();

        // 90 requests at 30ms, 10 requests at 850ms -> p95 should be ~850ms (> 400ms)
        for (int i = 0; i < 90; i++) {
            generator.recordResult(now, 30, true);
        }
        for (int i = 0; i < 10; i++) {
            generator.recordResult(now, 850, true);
        }

        LoadGenerator.SloStatus status = generator.getSloStatus();
        assertTrue(status.p95LatencyMs() >= 800.0);
        assertFalse(status.sloHealthy(), "SLO should be violated when p95 exceeds 400ms");
    }

    @Test
    void testViolatedSloDueToErrorRate() {
        LoadGenerator generator = new LoadGenerator();
        long now = System.currentTimeMillis();

        // 90 successes, 10 errors -> 10% error rate (> 3%)
        for (int i = 0; i < 90; i++) {
            generator.recordResult(now, 25, true);
        }
        for (int i = 0; i < 10; i++) {
            generator.recordResult(now, 25, false);
        }

        LoadGenerator.SloStatus status = generator.getSloStatus();
        assertEquals(0.10, status.errorRate(), 0.001);
        assertFalse(status.sloHealthy(), "SLO should be violated when error rate exceeds 3%");
    }
}
