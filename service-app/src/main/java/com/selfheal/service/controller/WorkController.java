package com.selfheal.service.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfheal.common.client.LinkClient;
import com.selfheal.common.telemetry.NodeMetricsCollector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Controller executing the synthetic microservice workload tree.
 */
@RestController
public class WorkController {

    @Value("${svc.name:unknown-service}")
    private String serviceName;

    @Value("${server.port:8080}")
    private int port;

    @Value("${svc.downstream:}")
    private String downstreamConfig;

    @Value("${svc.critical-downstream:post-storage}")
    private String criticalDownstream;

    private final LinkClient linkClient;
    private final NodeMetricsCollector metricsCollector;
    private final ObjectMapper objectMapper;

    public WorkController(LinkClient linkClient,
                          NodeMetricsCollector metricsCollector) {
        this.linkClient = linkClient;
        this.metricsCollector = metricsCollector;
        this.objectMapper = new ObjectMapper();
    }

    @GetMapping("/work")
    public ResponseEntity<Map<String, Object>> doWork() {
        metricsCollector.incrementRequestCount();

        // 1. Synthetic CPU burn (small mathematical computation)
        double cpuBurn = 0.0;
        for (int i = 0; i < 5000; i++) {
            cpuBurn += Math.sqrt(i);
        }

        // 2. Workload sleep: 5-10 ms
        try {
            int sleepMs = ThreadLocalRandom.current().nextInt(5, 11);
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean degraded = false;
        Map<String, Object> downstreamResults = new LinkedHashMap<>();

        // 3. Downstream delegation via LinkClient
        List<DownstreamTarget> targets = parseDownstream(downstreamConfig);
        for (DownstreamTarget target : targets) {
            String url = "http://127.0.0.1:" + target.port() + "/work";
            try {
                String responseBody = linkClient.call(target.name(), url, "GET", null);
                if (responseBody != null && !responseBody.isBlank()) {
                    Map<String, Object> childMap = objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {});
                    downstreamResults.put(target.name(), childMap);
                    if (Boolean.TRUE.equals(childMap.get("degraded"))) {
                        degraded = true;
                    }
                } else {
                    downstreamResults.put(target.name(), Map.of("status", "EMPTY"));
                }
            } catch (Exception e) {
                // Critical vs Non-critical downstream differentiation
                if (target.name().equalsIgnoreCase(criticalDownstream.trim())) {
                    throw new ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Critical downstream '" + target.name() + "' failed: " + e.getMessage(), e
                    );
                } else {
                    // Non-critical: fail-fast -> continue with degraded=true
                    degraded = true;
                    downstreamResults.put(target.name(), Map.of(
                            "status", "FAILED_NON_CRITICAL",
                            "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
                    ));
                }
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", serviceName);
        response.put("port", port);
        response.put("status", "OK");
        response.put("degraded", degraded);
        response.put("timestamp", System.currentTimeMillis());
        if (!downstreamResults.isEmpty()) {
            response.put("downstream", downstreamResults);
        }

        return ResponseEntity.ok(response);
    }

    private List<DownstreamTarget> parseDownstream(String config) {
        if (config == null || config.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<DownstreamTarget> targets = new ArrayList<>();
        String[] entries = config.split(",");
        for (String entry : entries) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split(":");
            if (parts.length == 2) {
                targets.add(new DownstreamTarget(parts[0].trim(), Integer.parseInt(parts[1].trim())));
            } else if (parts.length == 1) {
                targets.add(new DownstreamTarget(parts[0].trim(), 8080));
            }
        }
        return targets;
    }

    private record DownstreamTarget(String name, int port) {}
}
