package com.selfheal.common.telemetry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks fixed message template counters (e.g. LINK_TIMEOUT, DOWNSTREAM_ERROR).
 */
public class LogEventTracker {
    public static final String LINK_TIMEOUT = "LINK_TIMEOUT";
    public static final String DOWNSTREAM_ERROR = "DOWNSTREAM_ERROR";

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public LogEventTracker() {
        // Initialize standard required fixed counters
        counters.put(LINK_TIMEOUT, new AtomicLong(0));
        counters.put(DOWNSTREAM_ERROR, new AtomicLong(0));
    }

    public void increment(String eventType) {
        counters.computeIfAbsent(eventType, k -> new AtomicLong(0)).incrementAndGet();
    }

    public long getCount(String eventType) {
        AtomicLong counter = counters.get(eventType);
        return counter != null ? counter.get() : 0L;
    }

    public Map<String, Long> getSnapshot() {
        Map<String, Long> snapshot = new HashMap<>();
        counters.forEach((key, value) -> snapshot.put(key, value.get()));
        return Collections.unmodifiableMap(snapshot);
    }
}
