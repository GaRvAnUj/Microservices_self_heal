package com.selfheal.healer.controller;

import com.selfheal.healer.chaos.ChaosRegistry;
import com.selfheal.healer.collector.TelemetryCollectorService;
import com.selfheal.healer.engine.DiagnosisClient;
import com.selfheal.healer.engine.HealingEngine;
import com.selfheal.healer.engine.Incident;
import com.selfheal.healer.engine.PolicyService;
import com.selfheal.healer.engine.StatisticalLocalizer;
import com.selfheal.healer.load.LoadGenerator;
import com.selfheal.healer.service.ServiceManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.lang.management.ManagementFactory;

@RestController
@RequestMapping("/api/experiment")
public class ExperimentApiController {
    public record ResetRequest(String mode,
                               boolean wrongInitialLinkLossQ,
                               boolean plantedWrongTarget,
                               boolean resetQPolicy,
                               String expectedFaultTarget,
                               String expectedFaultType,
                               String diagnosisSourceMode) {}

    private final ChaosRegistry chaosRegistry;
    private final DiagnosisClient diagnosisClient;
    private final HealingEngine healingEngine;
    private final PolicyService policyService;
    private final ServiceManager serviceManager;
    private final LoadGenerator loadGenerator;
    private final TelemetryCollectorService telemetryCollector;

    public ExperimentApiController(ChaosRegistry chaosRegistry,
                                   DiagnosisClient diagnosisClient,
                                   HealingEngine healingEngine,
                                   PolicyService policyService,
                                   ServiceManager serviceManager,
                                   LoadGenerator loadGenerator,
                                   TelemetryCollectorService telemetryCollector) {
        this.chaosRegistry = chaosRegistry;
        this.diagnosisClient = diagnosisClient;
        this.healingEngine = healingEngine;
        this.policyService = policyService;
        this.serviceManager = serviceManager;
        this.loadGenerator = loadGenerator;
        this.telemetryCollector = telemetryCollector;
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset(@RequestBody ResetRequest request) {
        Incident.OperatingMode mode;
        try {
            mode = Incident.OperatingMode.valueOf(request.mode().trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid mode. Must be NO_HEAL, BASELINE, PROPOSED_NO_LINK, PROPOSED_NO_ATTRIB, or PROPOSED."
            ));
        }

        String diagnosisSourceMode = request.diagnosisSourceMode() == null
                ? "auto"
                : request.diagnosisSourceMode();
        try {
            DiagnosisClient.SourceMode.valueOf(diagnosisSourceMode.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Diagnosis source must be auto, modify, or fallback."
            ));
        }

        healingEngine.prepareExperiment(
                mode,
                request.plantedWrongTarget(),
                request.expectedFaultTarget(),
                request.expectedFaultType(),
                diagnosisSourceMode
        );
        chaosRegistry.clearAll();
        Map<String, Boolean> restarts = serviceManager.restartAll();
        if (request.resetQPolicy()) {
            policyService.resetForExperiment(request.wrongInitialLinkLossQ());
        }

        boolean allRestarted = restarts.values().stream().allMatch(Boolean::booleanValue);
        Map<String, Object> response = Map.of(
                "mode", mode.name(),
                "plantedWrongTarget", request.plantedWrongTarget(),
                "wrongInitialLinkLossQ", request.wrongInitialLinkLossQ(),
                "restarts", restarts
        );
        return ResponseEntity.status(allRestarted ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(response);
    }

    @PostMapping("/start")
    public Map<String, String> start() {
        healingEngine.startExperiment();
        return Map.of("status", "STARTED");
    }

    @GetMapping("/diagnosis-metrics")
    public Map<String, Object> diagnosisMetrics() {
        return diagnosisClient.getLatencyMetrics();
    }

    @GetMapping("/runtime")
    public Map<String, Object> runtime() {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("javaVersion", System.getProperty("java.version"));
        runtime.put("javaVendor", System.getProperty("java.vendor"));
        runtime.put("jvmFlags", ManagementFactory.getRuntimeMXBean().getInputArguments());
        runtime.put("jvmMaxMemoryBytes", Runtime.getRuntime().maxMemory());
        runtime.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        runtime.put("targetRps", loadGenerator.getTargetRps());
        runtime.put("windowLengthMs", LoadGenerator.WINDOW_MS);
        runtime.put("sloP95LimitMs", LoadGenerator.SLO_P95_LIMIT_MS);
        runtime.put("sloErrorLimit", LoadGenerator.SLO_ERROR_LIMIT);
        runtime.put("telemetryPollIntervalMs", TelemetryCollectorService.TELEMETRY_POLL_INTERVAL_MS);
        runtime.put("baselineWarmupSamples", TelemetryCollectorService.BASELINE_WARMUP_REQUIRED_SAMPLES);
        runtime.put("baselineEstablished", telemetryCollector.isBaselineEstablished());
        runtime.put("baselineProfileDurationMs", telemetryCollector.getBaselineProfileDurationMs());
        runtime.put("anomalyZThreshold", HealingEngine.ANOMALY_Z_THRESHOLD);
        runtime.put("linkCandidateZThreshold", StatisticalLocalizer.LINK_CANDIDATE_Z_THRESHOLD);
        runtime.put("calleeAbnormalityZThreshold", StatisticalLocalizer.CALLEE_ABNORMALITY_Z_THRESHOLD);
        runtime.put("attributionLowZThreshold", HealingEngine.ATTRIBUTION_LOW_Z_THRESHOLD);
        runtime.put("attributionDominanceRatio", HealingEngine.ATTRIBUTION_DOMINANCE_RATIO);
        runtime.put("ports", Map.of(
                "healer", 8080,
                "gateway", 8081,
                "compose-post", 8082,
                "text", 8083,
                "media", 8084,
                "user", 8085,
                "post-storage", 8086,
                "diagnosis", 5000
        ));
        return runtime;
    }
}
