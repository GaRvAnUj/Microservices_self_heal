package com.selfheal.common.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe accumulator of link statistics for a directed edge (caller -> target).
 */
public class EdgeStats {
    private final String caller;
    private final String target;
    private final AtomicLong callCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicLong latencySumMs = new AtomicLong(0);
    private final AtomicLong bytesIn = new AtomicLong(0);
    private final AtomicLong bytesOut = new AtomicLong(0);

    public EdgeStats(String caller, String target) {
        this.caller = caller;
        this.target = target;
    }

    public void recordCall(long latencyMs, boolean error, long inBytes, long outBytes) {
        callCount.incrementAndGet();
        if (error) {
            errorCount.incrementAndGet();
        }
        latencySumMs.addAndGet(Math.max(0, latencyMs));
        bytesIn.addAndGet(Math.max(0, inBytes));
        bytesOut.addAndGet(Math.max(0, outBytes));
    }

    public String getCaller() {
        return caller;
    }

    public String getTarget() {
        return target;
    }

    public long getCallCount() {
        return callCount.get();
    }

    public long getErrorCount() {
        return errorCount.get();
    }

    public long getLatencySumMs() {
        return latencySumMs.get();
    }

    public long getBytesIn() {
        return bytesIn.get();
    }

    public long getBytesOut() {
        return bytesOut.get();
    }

    public double getAvgLatencyMs() {
        long calls = callCount.get();
        return calls == 0 ? 0.0 : (double) latencySumMs.get() / calls;
    }

    public double getErrorRate() {
        long calls = callCount.get();
        return calls == 0 ? 0.0 : (double) errorCount.get() / calls;
    }

    /**
     * Creates an immutable snapshot for telemetry reports.
     */
    public Snapshot snapshot() {
        long calls = callCount.get();
        long errors = errorCount.get();
        long latencySum = latencySumMs.get();
        long inBytes = bytesIn.get();
        long outBytes = bytesOut.get();
        double avgLat = calls == 0 ? 0.0 : (double) latencySum / calls;
        double errRate = calls == 0 ? 0.0 : (double) errors / calls;

        return new Snapshot(caller, target, calls, errors, latencySum, avgLat, errRate, inBytes, outBytes);
    }

    public static class Snapshot {
        private final String caller;
        private final String target;
        private final long callCount;
        private final long errorCount;
        private final long latencySumMs;
        private final double avgLatencyMs;
        private final double errorRate;
        private final long bytesIn;
        private final long bytesOut;

        public Snapshot(String caller, String target, long callCount, long errorCount,
                        long latencySumMs, double avgLatencyMs, double errorRate,
                        long bytesIn, long bytesOut) {
            this.caller = caller;
            this.target = target;
            this.callCount = callCount;
            this.errorCount = errorCount;
            this.latencySumMs = latencySumMs;
            this.avgLatencyMs = avgLatencyMs;
            this.errorRate = errorRate;
            this.bytesIn = bytesIn;
            this.bytesOut = bytesOut;
        }

        public String getCaller() { return caller; }
        public String getTarget() { return target; }
        public long getCallCount() { return callCount; }
        public long getErrorCount() { return errorCount; }
        public long getLatencySumMs() { return latencySumMs; }
        public double getAvgLatencyMs() { return avgLatencyMs; }
        public double getErrorRate() { return errorRate; }
        public long getBytesIn() { return bytesIn; }
        public long getBytesOut() { return bytesOut; }
    }
}
