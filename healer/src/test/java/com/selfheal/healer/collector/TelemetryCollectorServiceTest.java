package com.selfheal.healer.collector;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class TelemetryCollectorServiceTest {

    @Test
    void testComputeSmoothedSampleWithEmptyWindows() {
        Map<String, Double> emptyResult = TelemetryCollectorService.computeSmoothedSample(null);
        assertTrue(emptyResult.isEmpty());

        emptyResult = TelemetryCollectorService.computeSmoothedSample(List.of());
        assertTrue(emptyResult.isEmpty());
    }

    @Test
    void testComputeSmoothedSampleAveragesValues() {
        Map<String, Double> win1 = Map.of(
                "node:gateway:cpu_process", 0.10,
                "link:gateway->compose-post:avg_latency", 20.0
        );
        Map<String, Double> win2 = Map.of(
                "node:gateway:cpu_process", 0.20,
                "link:gateway->compose-post:avg_latency", 30.0
        );
        Map<String, Double> win3 = Map.of(
                "node:gateway:cpu_process", 0.30,
                "link:gateway->compose-post:avg_latency", 40.0
        );

        Map<String, Double> smoothed = TelemetryCollectorService.computeSmoothedSample(List.of(win1, win2, win3));

        assertEquals(2, smoothed.size());
        assertEquals(0.20, smoothed.get("node:gateway:cpu_process"), 0.0001);
        assertEquals(30.0, smoothed.get("link:gateway->compose-post:avg_latency"), 0.0001);
    }

    @Test
    void testComputeSmoothedSampleHandlesMissingKeysGracefully() {
        Map<String, Double> win1 = Map.of("signalA", 10.0, "signalB", 100.0);
        Map<String, Double> win2 = Map.of("signalA", 20.0);

        Map<String, Double> smoothed = TelemetryCollectorService.computeSmoothedSample(List.of(win1, win2));

        assertEquals(15.0, smoothed.get("signalA"), 0.0001);
        assertEquals(100.0, smoothed.get("signalB"), 0.0001);
    }
}
