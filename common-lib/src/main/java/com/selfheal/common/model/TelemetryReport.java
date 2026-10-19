package com.selfheal.common.model;

import java.util.List;
import java.util.Map;

/**
 * Complete snapshot report emitted by GET /internal/telemetry every 3s.
 */
public class TelemetryReport {
    private String serviceName;
    private int port;
    private long timestamp;
    private NodeMetrics nodeMetrics;
    private List<EdgeStats.Snapshot> edgeStats;
    private Map<String, Long> logEventCounters;

    public TelemetryReport() {
    }

    public TelemetryReport(String serviceName, int port, long timestamp,
                           NodeMetrics nodeMetrics, List<EdgeStats.Snapshot> edgeStats,
                           Map<String, Long> logEventCounters) {
        this.serviceName = serviceName;
        this.port = port;
        this.timestamp = timestamp;
        this.nodeMetrics = nodeMetrics;
        this.edgeStats = edgeStats;
        this.logEventCounters = logEventCounters;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public NodeMetrics getNodeMetrics() {
        return nodeMetrics;
    }

    public void setNodeMetrics(NodeMetrics nodeMetrics) {
        this.nodeMetrics = nodeMetrics;
    }

    public List<EdgeStats.Snapshot> getEdgeStats() {
        return edgeStats;
    }

    public void setEdgeStats(List<EdgeStats.Snapshot> edgeStats) {
        this.edgeStats = edgeStats;
    }

    public Map<String, Long> getLogEventCounters() {
        return logEventCounters;
    }

    public void setLogEventCounters(Map<String, Long> logEventCounters) {
        this.logEventCounters = logEventCounters;
    }
}
