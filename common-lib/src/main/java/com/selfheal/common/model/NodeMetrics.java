package com.selfheal.common.model;

/**
 * Node-level operating system and JVM telemetry metrics.
 */
public class NodeMetrics {
    private double processCpuLoad;
    private double systemCpuLoad;
    private long heapUsedBytes;
    private long heapCommittedBytes;
    private long requestCount;

    public NodeMetrics() {
    }

    public NodeMetrics(double processCpuLoad, double systemCpuLoad, long heapUsedBytes,
                       long heapCommittedBytes, long requestCount) {
        this.processCpuLoad = processCpuLoad;
        this.systemCpuLoad = systemCpuLoad;
        this.heapUsedBytes = heapUsedBytes;
        this.heapCommittedBytes = heapCommittedBytes;
        this.requestCount = requestCount;
    }

    public double getProcessCpuLoad() {
        return processCpuLoad;
    }

    public void setProcessCpuLoad(double processCpuLoad) {
        this.processCpuLoad = processCpuLoad;
    }

    public double getSystemCpuLoad() {
        return systemCpuLoad;
    }

    public void setSystemCpuLoad(double systemCpuLoad) {
        this.systemCpuLoad = systemCpuLoad;
    }

    public long getHeapUsedBytes() {
        return heapUsedBytes;
    }

    public void setHeapUsedBytes(long heapUsedBytes) {
        this.heapUsedBytes = heapUsedBytes;
    }

    public long getHeapCommittedBytes() {
        return heapCommittedBytes;
    }

    public void setHeapCommittedBytes(long heapCommittedBytes) {
        this.heapCommittedBytes = heapCommittedBytes;
    }

    public long getRequestCount() {
        return requestCount;
    }

    public void setRequestCount(long requestCount) {
        this.requestCount = requestCount;
    }

    @Override
    public String toString() {
        return "NodeMetrics{" +
               "processCpuLoad=" + processCpuLoad +
               ", systemCpuLoad=" + systemCpuLoad +
               ", heapUsedBytes=" + heapUsedBytes +
               ", heapCommittedBytes=" + heapCommittedBytes +
               ", requestCount=" + requestCount +
               '}';
    }
}
