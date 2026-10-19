package com.selfheal.common.telemetry;

import com.selfheal.common.model.NodeMetrics;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects process and system CPU, heap, and request metrics.
 */
public class NodeMetricsCollector {
    private final OperatingSystemMXBean osBean;
    private final MemoryMXBean memoryBean;
    private final AtomicLong requestCounter = new AtomicLong(0);

    public NodeMetricsCollector() {
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.memoryBean = ManagementFactory.getMemoryMXBean();
    }

    public void incrementRequestCount() {
        requestCounter.incrementAndGet();
    }

    public long getRequestCount() {
        return requestCounter.get();
    }

    public NodeMetrics collect() {
        double processCpu = 0.0;
        double systemCpu = 0.0;

        // HotSpot / OpenJDK OS MXBean has process/system CPU load methods
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
            double pCpu = sunOsBean.getProcessCpuLoad();
            double sCpu = sunOsBean.getCpuLoad();
            processCpu = pCpu < 0 ? 0.0 : pCpu;
            systemCpu = sCpu < 0 ? 0.0 : sCpu;
        }

        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long heapUsed = heapUsage.getUsed();
        long heapCommitted = heapUsage.getCommitted();
        long requests = requestCounter.get();

        return new NodeMetrics(processCpu, systemCpu, heapUsed, heapCommitted, requests);
    }
}
