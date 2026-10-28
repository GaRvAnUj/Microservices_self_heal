package com.selfheal.common.controller;

import com.selfheal.common.client.LinkClient;
import com.selfheal.common.model.EdgePolicy;
import com.selfheal.common.model.NodeMetrics;
import com.selfheal.common.model.TelemetryReport;
import com.selfheal.common.policy.EdgePolicyRegistry;
import com.selfheal.common.telemetry.LogEventTracker;
import com.selfheal.common.telemetry.NodeMetricsCollector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller exposing standard chaos, telemetry, and policy-tuning endpoints on every microservice.
 * Bound to localhost only.
 */
@RestController
public class CommonChaosController {

    @Value("${svc.name:unknown-service}")
    private String serviceName;

    @Value("${server.port:8080}")
    private int port;

    private final EdgePolicyRegistry policyRegistry;
    private final LinkClient linkClient;
    private final NodeMetricsCollector metricsCollector;
    private final LogEventTracker logTracker;

    // CPU hog threads
    private final List<Thread> cpuHogThreads = new CopyOnWriteArrayList<>();
    private final AtomicBoolean cpuHogActive = new AtomicBoolean(false);

    public CommonChaosController(EdgePolicyRegistry policyRegistry,
                                 LinkClient linkClient,
                                 NodeMetricsCollector metricsCollector,
                                 LogEventTracker logTracker) {
        this.policyRegistry = policyRegistry;
        this.linkClient = linkClient;
        this.metricsCollector = metricsCollector;
        this.logTracker = logTracker;
    }

    /**
     * Telemetry polled by the Healer Collector every 3s.
     */
    @GetMapping("/internal/telemetry")
    public TelemetryReport getTelemetry() {
        NodeMetrics nodeMetrics = metricsCollector.collect();
        return new TelemetryReport(
                serviceName,
                port,
                System.currentTimeMillis(),
                nodeMetrics,
                linkClient.getEdgeStatsSnapshots(),
                logTracker.getSnapshot()
        );
    }

    /**
     * Dynamic link policy update (N2: Fault-type-driven healing without restart).
     */
    @PutMapping("/internal/policy/{target}")
    public ResponseEntity<Map<String, Object>> updatePolicy(
            @PathVariable("target") String target,
            @RequestBody(required = false) EdgePolicy body,
            @RequestParam(value = "timeoutMs", required = false) Integer timeoutMs,
            @RequestParam(value = "maxRetries", required = false) Integer maxRetries,
            @RequestParam(value = "retryBudgetPct", required = false) Integer retryBudgetPct,
            @RequestParam(value = "breakerOpen", required = false) Boolean breakerOpen) {

        EdgePolicy current = policyRegistry.getPolicy(target);
        int t = (body != null && body.getTimeoutMs() > 0) ? body.getTimeoutMs() : (timeoutMs != null ? timeoutMs : (current != null ? current.getTimeoutMs() : 800));
        int r = (body != null) ? body.getMaxRetries() : (maxRetries != null ? maxRetries : (current != null ? current.getMaxRetries() : 2));
        int b = (body != null) ? body.getRetryBudgetPct() : (retryBudgetPct != null ? retryBudgetPct : (current != null ? current.getRetryBudgetPct() : 20));
        boolean brk = (body != null) ? body.isBreakerOpen() : (breakerOpen != null ? breakerOpen : (current != null ? current.isBreakerOpen() : false));

        EdgePolicy updatedPolicy = new EdgePolicy(t, r, b, brk);
        policyRegistry.updatePolicy(target, updatedPolicy);
        return ResponseEntity.ok(Map.of(
                "status", "UPDATED",
                "target", target,
                "policy", updatedPolicy
        ));
    }

    /**
     * Chaos endpoint: Burn CPU using a bounded number of busy-loop threads.
     */
    @PostMapping("/chaos/cpu")
    public ResponseEntity<Map<String, Object>> toggleCpuHog(
            @RequestParam("on") boolean on,
            @RequestParam(value = "threads", required = false) Integer requestedThreads) {
        if (on) {
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            int threads = requestedThreads == null ? availableProcessors : requestedThreads;
            if (threads < 1 || threads > availableProcessors) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "threads must be between 1 and " + availableProcessors
                ));
            }
            if (cpuHogActive.compareAndSet(false, true)) {
                for (int i = 0; i < threads; i++) {
                    Thread t = new Thread(() -> {
                        while (cpuHogActive.get()) {
                            // Busy loop to burn CPU
                            double val = Math.sqrt(Math.random() * 1000.0);
                        }
                    }, "cpu-hog-" + i);
                    t.setDaemon(true);
                    cpuHogThreads.add(t);
                    t.start();
                }
            }
            return ResponseEntity.ok(Map.of("status", "CPU hog active", "threads", cpuHogThreads.size()));
        } else {
            cpuHogActive.set(false);
            cpuHogThreads.clear();
            return ResponseEntity.ok(Map.of("status", "CPU hog stopped"));
        }
    }

    /**
     * Chaos endpoint: Respond 200 then abruptly terminate JVM process via Runtime.halt(1).
     */
    @PostMapping("/chaos/crash")
    public ResponseEntity<Map<String, String>> crashService() {
        // Schedule halt after short delay so HTTP response is returned
        Thread crashThread = new Thread(() -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {}
            Runtime.getRuntime().halt(1);
        }, "chaos-crasher");
        crashThread.setDaemon(false);
        crashThread.start();

        return ResponseEntity.ok(Map.of("status", "crashing", "service", serviceName));
    }
}
