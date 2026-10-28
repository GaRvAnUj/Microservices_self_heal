package com.selfheal.healer.controller;

import com.selfheal.healer.chaos.ChaosRegistry;
import com.selfheal.healer.collector.TelemetryCollectorService;
import com.selfheal.healer.engine.HealingEngine;
import com.selfheal.healer.engine.Incident;
import com.selfheal.healer.engine.PolicyService;
import com.selfheal.healer.load.LoadGenerator;
import com.selfheal.healer.service.ServiceManager;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controller providing REST and Server-Sent Events (SSE) APIs for the Healer Dashboard.
 * Strictly bound to 127.0.0.1.
 */
@RestController
@RequestMapping("/api")
public class DashboardApiController {
    private static final Logger log = Logger.getLogger(DashboardApiController.class.getName());

    private final ServiceManager serviceManager;
    private final LoadGenerator loadGenerator;
    private final TelemetryCollectorService telemetryCollector;
    private final HealingEngine healingEngine;
    private final PolicyService policyService;
    private final ChaosRegistry chaosRegistry;
    private final HttpClient httpClient;

    private final List<SseEmitter> activeEmitters = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService sseBroadcastScheduler;

    public DashboardApiController(ServiceManager serviceManager,
                                  LoadGenerator loadGenerator,
                                  TelemetryCollectorService telemetryCollector,
                                  HealingEngine healingEngine,
                                  PolicyService policyService,
                                  ChaosRegistry chaosRegistry) {
        this.serviceManager = serviceManager;
        this.loadGenerator = loadGenerator;
        this.telemetryCollector = telemetryCollector;
        this.healingEngine = healingEngine;
        this.policyService = policyService;
        this.chaosRegistry = chaosRegistry;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(800))
                .build();

        this.sseBroadcastScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dashboard-sse-broadcaster");
            t.setDaemon(true);
            return t;
        });
        // Stream state every 1 second
        this.sseBroadcastScheduler.scheduleWithFixedDelay(this::broadcastSseUpdate, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void cleanup() {
        sseBroadcastScheduler.shutdownNow();
        for (SseEmitter emitter : activeEmitters) {
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        }
        activeEmitters.clear();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamUpdates() {
        SseEmitter emitter = new SseEmitter(180_000L); // 3 minutes timeout before reconnect
        activeEmitters.add(emitter);

        emitter.onCompletion(() -> activeEmitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            activeEmitters.remove(emitter);
        });
        emitter.onError(e -> activeEmitters.remove(emitter));

        // Immediately send initial snapshot
        try {
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data(buildStatusSnapshot()));
        } catch (IOException e) {
            activeEmitters.remove(emitter);
        }

        return emitter;
    }

    private void broadcastSseUpdate() {
        if (activeEmitters.isEmpty()) return;

        Map<String, Object> snapshot = buildStatusSnapshot();
        List<SseEmitter> deadEmitters = new ArrayList<>();

        for (SseEmitter emitter : activeEmitters) {
            try {
                emitter.send(SseEmitter.event().name("status").data(snapshot));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }
        activeEmitters.removeAll(deadEmitters);
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        return buildStatusSnapshot();
    }

    private Map<String, Object> buildStatusSnapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", System.currentTimeMillis());
        map.put("mode", healingEngine.getMode().name());
        map.put("slo", loadGenerator.getSloStatus());
        map.put("services", serviceManager.getStatuses());
        map.put("activeIncident", healingEngine.getActiveIncident());
        map.put("incidentHistory", healingEngine.getIncidentHistory());
        map.put("chaosFaults", chaosRegistry.getAllFaults());
        map.put("qPolicy", policyService.getAllQEntries());
        map.put("zScores", telemetryCollector.getLatestZScores());
        map.put("smoothedSignals", telemetryCollector.getLatestSmoothedSample());
        map.put("baselineEstablished", telemetryCollector.isBaselineEstablished());
        map.put("baselineSignalCount", telemetryCollector.getBaselineSignalCount());
        map.put("baselineProfileDurationMs", telemetryCollector.getBaselineProfileDurationMs());
        return map;
    }

    @PostMapping("/mode")
    public ResponseEntity<Map<String, String>> setMode(@RequestParam("mode") String modeStr) {
        try {
            Incident.OperatingMode newMode = Incident.OperatingMode.valueOf(modeStr.toUpperCase().trim());
            healingEngine.setMode(newMode);
            log.info("Dashboard switched operating mode to: " + newMode);
            return ResponseEntity.ok(Map.of("status", "UPDATED", "mode", newMode.name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid mode. Must be BASELINE or PROPOSED"));
        }
    }

    @PostMapping("/service/{name}/restart")
    public ResponseEntity<Map<String, Object>> restartService(@PathVariable("name") String name) {
        boolean ok = serviceManager.restartService(name);
        return ResponseEntity.ok(Map.of("service", name, "success", ok));
    }

    @PostMapping("/chaos/inject")
    public ResponseEntity<Map<String, Object>> injectChaos(
            @RequestParam("type") String type,
            @RequestParam(value = "caller", required = false) String caller,
            @RequestParam(value = "target", required = false) String target,
            @RequestParam(value = "delayMs", defaultValue = "0") int delayMs,
            @RequestParam(value = "lossPct", defaultValue = "0.0") double lossPct,
            @RequestParam(value = "threads", defaultValue = "0") int threads) {

        String lowerType = type.toLowerCase().trim();

        if ("link_delay".equals(lowerType) || "link_loss".equals(lowerType)) {
            if (caller == null || target == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "caller and target required for link chaos"));
            }
            chaosRegistry.setFault(caller, target, delayMs, lossPct);
            return ResponseEntity.ok(Map.of(
                    "status", "INJECTED",
                    "type", lowerType,
                    "caller", caller,
                    "target", target,
                    "delayMs", delayMs,
                    "lossPct", lossPct
            ));
        }

        if ("cpu_hog".equals(lowerType)) {
            if (target == null) return ResponseEntity.badRequest().body(Map.of("error", "target required"));
            ServiceManager.ServiceDef def = serviceManager.getServiceDef(target);
            if (def == null) return ResponseEntity.badRequest().body(Map.of("error", "Unknown service: " + target));

            if (threads < 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "threads must be non-negative"));
            }
            String threadParameter = threads == 0 ? "" : "&threads=" + threads;
            if (!callServiceChaos(def.port(), "/chaos/cpu?on=true" + threadParameter)) {
                return ResponseEntity.status(502).body(Map.of("error", "CPU fault injection failed for " + target));
            }
            return ResponseEntity.ok(Map.of(
                    "status", "INJECTED",
                    "type", "cpu_hog",
                    "target", target,
                    "threads", threads == 0 ? Runtime.getRuntime().availableProcessors() : threads
            ));
        }

        if ("crash".equals(lowerType)) {
            if (target == null) return ResponseEntity.badRequest().body(Map.of("error", "target required"));
            ServiceManager.ServiceDef def = serviceManager.getServiceDef(target);
            if (def == null) return ResponseEntity.badRequest().body(Map.of("error", "Unknown service: " + target));

            if (!callServiceChaos(def.port(), "/chaos/crash")) {
                return ResponseEntity.status(502).body(Map.of("error", "Crash fault injection failed for " + target));
            }
            return ResponseEntity.ok(Map.of("status", "INJECTED", "type", "crash", "target", target));
        }

        return ResponseEntity.badRequest().body(Map.of("error", "Unknown chaos type: " + type));
    }

    @PostMapping("/chaos/clear")
    public ResponseEntity<Map<String, String>> clearAllChaos() {
        // 1. Clear link faults
        chaosRegistry.clearAll();

        // 2. Clear CPU hog across all services
        for (ServiceManager.ServiceDef def : serviceManager.getServiceDefs()) {
            callServiceChaos(def.port(), "/chaos/cpu?on=false");
        }

        return ResponseEntity.ok(Map.of("status", "ALL_CHAOS_CLEARED"));
    }

    private boolean callServiceChaos(int port, String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofMillis(800))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warning("Chaos call to port " + port + path + " returned HTTP " + response.statusCode());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.log(Level.WARNING, "Chaos call to port " + port + path + " was interrupted.", e);
            return false;
        } catch (IOException e) {
            log.warning("Chaos call to port " + port + path + " failed: " + e.getMessage());
            return false;
        }
    }
}
