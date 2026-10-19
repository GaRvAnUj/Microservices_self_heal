package com.selfheal.common.telemetry;

import com.selfheal.common.model.NodeMetrics;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class TelemetryTest {

    @Test
    void testNodeMetricsCollector() {
        NodeMetricsCollector collector = new NodeMetricsCollector();
        collector.incrementRequestCount();
        collector.incrementRequestCount();

        NodeMetrics metrics = collector.collect();
        assertNotNull(metrics);
        assertEquals(2, metrics.getRequestCount());
        assertTrue(metrics.getHeapCommittedBytes() > 0);
        assertTrue(metrics.getProcessCpuLoad() >= 0.0);
    }

    @Test
    void testLogEventTracker() {
        LogEventTracker tracker = new LogEventTracker();
        assertEquals(0, tracker.getCount(LogEventTracker.LINK_TIMEOUT));
        assertEquals(0, tracker.getCount(LogEventTracker.DOWNSTREAM_ERROR));

        tracker.increment(LogEventTracker.LINK_TIMEOUT);
        tracker.increment(LogEventTracker.LINK_TIMEOUT);
        tracker.increment(LogEventTracker.DOWNSTREAM_ERROR);

        assertEquals(2, tracker.getCount(LogEventTracker.LINK_TIMEOUT));
        assertEquals(1, tracker.getCount(LogEventTracker.DOWNSTREAM_ERROR));

        Map<String, Long> snapshot = tracker.getSnapshot();
        assertEquals(2L, snapshot.get(LogEventTracker.LINK_TIMEOUT));
        assertEquals(1L, snapshot.get(LogEventTracker.DOWNSTREAM_ERROR));
    }
}
