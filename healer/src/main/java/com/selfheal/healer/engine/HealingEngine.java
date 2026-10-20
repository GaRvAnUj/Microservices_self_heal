package com.selfheal.healer.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfheal.healer.collector.TelemetryCollectorService;
import com.selfheal.healer.load.LoadGenerator;
import com.selfheal.healer.service.ServiceManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
 * Closed-loop resilience engine implementing:
 * - Baseline mode: Restart max abnormality service.
 * - Proposed mode: N1 (Link diagnosis), N2 (Fault-type healing), and N3 (Attribution).
 */
@Service
public class HealingEngine {
    private static final Logger log = Logger.getLogger(HealingEngine.class.getName());

    private final ServiceManager serviceManager;
    private final LoadGenerator loadGenerator;
    private final TelemetryCollectorService telemetryCollector;
    private final DiagnosisClient diagnosisClient;
    private final PolicyService policyService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    private volatile Incident.OperatingMode mode = Incident.OperatingMode.PROPOSED;
    private volatile Incident activeIncident = null;
    private final List<Incident> incidentHistory = new CopyOnWriteArrayList<>();

    private ScheduledExecutorService evaluationScheduler;
    private int consecutiveAnomalySamples = 0;

    public HealingEngine(ServiceManager serviceManager,
                         LoadGenerator loadGenerator,
                         TelemetryCollectorService telemetryCollector,
                         DiagnosisClient diagnosisClient,
                         PolicyService policyService,
                         JdbcTemplate jdbcTemplate) {
        this.serviceManager = serviceManager;
        this.loadGenerator = loadGenerator;
        this.telemetryCollector = telemetryCollector;
        this.diagnosisClient = diagnosisClient;
        this.policyService = policyService;
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(800))
                .build();
    }

    @PostConstruct
    public synchronized void start() {
        if (evaluationScheduler == null || evaluationScheduler.isShutdown()) {
            evaluationScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "healing-engine-eval");
                t.setDaemon(true);
                return t;
            });
            // Evaluate every 3 seconds aligned with telemetry window
            evaluationScheduler.scheduleWithFixedDelay(this::evaluateCycle, 3, 3, TimeUnit.SECONDS);
            log.info("HealingEngine started in " + mode + " mode.");
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (evaluationScheduler != null) {
            evaluationScheduler.shutdownNow();
            evaluationScheduler = null;
        }
    }

    public synchronized void evaluateCycle() {
        try {
            LoadGenerator.SloStatus slo = loadGenerator.getSloStatus();
            Map<String, Double> zScores = telemetryCollector.getLatestZScores();
            Map<String, Double> smoothed = telemetryCollector.getLatestSmoothedSample();

            double maxZ = findMaxZScore(zScores);
            if (maxZ >= 4.0) {
                consecutiveAnomalySamples++;
            } else {
                consecutiveAnomalySamples = 0;
            }

            boolean triggerNeeded = !slo.sloHealthy() || consecutiveAnomalySamples >= 2;

            if (activeIncident == null) {
                if (triggerNeeded) {
                    openIncident(slo, maxZ);
                }
            } else {
                progressActiveIncident(slo, zScores, smoothed);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Error in HealingEngine evaluation cycle: " + e.getMessage(), e);
        }
    }

    private void openIncident(LoadGenerator.SloStatus slo, double maxZ) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String reason = !slo.sloHealthy()
                ? String.format("SLO Breached (p95=%.1fms, errRate=%.2f%%)", slo.p95LatencyMs(), slo.errorRate() * 100)
                : String.format("Statistical Anomaly (max z=%.2f)", maxZ);

        activeIncident = new Incident(id, now, reason, this.mode);
        log.info(String.format("OPENED INCIDENT [%s] under %s mode: %s", id, mode, reason));

        jdbcTemplate.update(
                "INSERT INTO incidents (id, started_at, trigger_reason, mode, final_status) VALUES (?, ?, ?, ?, ?)",
                id, now, reason, mode.name(), "OPEN"
        );
    }

    private void progressActiveIncident(LoadGenerator.SloStatus slo,
                                        Map<String, Double> zScores,
                                        Map<String, Double> smoothed) {
        Incident incident = activeIncident;
        if (incident == null) return;

        switch (incident.getStatus()) {
            case OPEN -> {
                incident.setStatus(Incident.Status.DIAGNOSING);
                dispatchRemediationAction(incident, zScores, smoothed);
            }
            case VERIFYING -> {
                int left = incident.decrementVerificationWindow();
                log.info(String.format("Incident [%s] verification window: %d remaining...", incident.getId(), left));
                if (left <= 0) {
                    evaluateVerificationOutcome(incident, slo, zScores, smoothed);
                }
            }
            default -> {}
        }
    }

    private void dispatchRemediationAction(Incident incident,
                                           Map<String, Double> zScores,
                                           Map<String, Double> smoothed) {
        int stepNum = incident.getActions().size() + 1;
        String actionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        if (incident.getMode() == Incident.OperatingMode.BASELINE) {
            // BASELINE: Blindly restart service with highest abnormality
            String targetService = findMaxAbnormalService(zScores);
            incident.setDiagnosis(targetService, "unknown_node_fault");

            Incident.IncidentActionRecord action = new Incident.IncidentActionRecord(
                    actionId, stepNum, "restart", targetService, now
            );
            incident.addAction(action);
            recordActionDb(incident.getId(), action);

            log.info(String.format("BASELINE action dispatch: restarting process %s", targetService));
            serviceManager.restartService(targetService);
            incident.startVerification(3);

        } else {
            // PROPOSED: N1 Diagnosis + N2 Fault-type action
            DiagnosisClient.DiagnosisResult diagnosis = diagnosisClient.diagnose(zScores, smoothed);
            String target = diagnosis.targetEntity();
            String faultType = diagnosis.faultType();
            if (target == null || target.isBlank()) {
                target = findMaxAbnormalService(zScores);
                faultType = "cpu_hog";
            }
            incident.setDiagnosis(target, faultType);

            String actionType = policyService.getBestAction(faultType);
            Incident.IncidentActionRecord action = new Incident.IncidentActionRecord(
                    actionId, stepNum, actionType, target, now
            );
            incident.addAction(action);
            recordActionDb(incident.getId(), action);

            log.info(String.format("PROPOSED action dispatch: [%s] on target [%s] for [%s] (source: %s)",
                    actionType, target, faultType, diagnosis.diagnosisSource()));

            executeProposedAction(target, faultType, actionType);
            incident.startVerification(3);
        }
    }

    private void executeProposedAction(String target, String faultType, String actionType) {
        if ("restart".equalsIgnoreCase(actionType) || !target.contains("->")) {
            // Node fault -> process restart
            serviceManager.restartService(target);
        } else {
            // Link fault -> zero-downtime policy update on caller
            String[] parts = target.split("->");
            String caller = parts[0].trim();
            String callee = parts.length > 1 ? parts[1].trim() : "";

            ServiceManager.ServiceDef callerDef = serviceManager.getServiceDef(caller);
            if (callerDef != null) {
                int timeoutMs = 800;
                int maxRetries = 2;
                int budget = 20;
                boolean breakerOpen = false;

                if ("tune_link".equalsIgnoreCase(actionType)) {
                    timeoutMs = 250; // aggressively reduce timeout
                    maxRetries = 0;  // fail fast without compounding retries
                    budget = 0;
                } else if ("breaker".equalsIgnoreCase(actionType)) {
                    breakerOpen = true; // trip circuit breaker
                }

                String url = String.format("http://127.0.0.1:%d/internal/policy/%s?timeoutMs=%d&maxRetries=%d&retryBudgetPct=%d&breakerOpen=%b",
                        callerDef.port(), callee, timeoutMs, maxRetries, budget, breakerOpen);
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofMillis(800))
                            .PUT(HttpRequest.BodyPublishers.noBody())
                            .build();
                    httpClient.send(req, HttpResponse.BodyHandlers.discarding());
                    log.info("Applied runtime link policy to " + url);
                } catch (Exception e) {
                    log.warning("Failed to dispatch link policy update: " + e.getMessage());
                }
            }
        }
    }

    private void evaluateVerificationOutcome(Incident incident,
                                             LoadGenerator.SloStatus slo,
                                             Map<String, Double> zScores,
                                             Map<String, Double> smoothed) {
        long now = System.currentTimeMillis();
        Incident.IncidentActionRecord latest = incident.getLatestAction();
        String target = incident.getTargetEntity();
        String faultType = incident.getRootCauseType();

        double postActionZ = computeComponentZ(target, zScores);

        if (slo.sloHealthy()) {
            // SUCCESS
            if (latest != null) {
                latest.complete(now, postActionZ, "SUCCESS");
                updateActionDb(latest);
            }
            incident.resolve(now);
            updateIncidentDb(incident, "RESOLVED");

            if (incident.getMode() == Incident.OperatingMode.PROPOSED && latest != null) {
                policyService.rewardAction(faultType, latest.getActionType());
                try {
                    String featuresJson = mapper.writeValueAsString(smoothed);
                    policyService.saveLabelledSample(target, faultType, featuresJson);
                } catch (Exception ignored) {}
            }

            log.info(String.format("INCIDENT RESOLVED [%s] in %d ms!", incident.getId(), (now - incident.getStartedAt())));
            incidentHistory.add(incident);
            activeIncident = null;

        } else {
            // REMEDIATION FAILED: perform N3 Attribution if in PROPOSED mode
            if (incident.getMode() == Incident.OperatingMode.BASELINE) {
                if (latest != null) {
                    latest.complete(now, postActionZ, "INEFFECTIVE_ACTION");
                    updateActionDb(latest);
                }
                if (incident.getActions().size() >= 3) {
                    incident.escalate();
                    updateIncidentDb(incident, "ESCALATED");
                    incidentHistory.add(incident);
                    activeIncident = null;
                } else {
                    dispatchRemediationAction(incident, zScores, smoothed);
                }
            } else {
                // N3: Recovery-Failure Attribution Logic
                String verdict = determineAttributionVerdict(target, postActionZ, zScores);
                if (latest != null) {
                    latest.complete(now, postActionZ, verdict);
                    updateActionDb(latest);
                }

                log.warning(String.format("Remediation verification failed. N3 Attribution: %s (postActionZ=%.2f)",
                        verdict, postActionZ));

                if ("INEFFECTIVE_ACTION".equals(verdict) && latest != null) {
                    policyService.penalizeAction(faultType, latest.getActionType());
                }

                if (incident.getActions().size() >= 3) {
                    incident.escalate();
                    updateIncidentDb(incident, "ESCALATED");
                    incidentHistory.add(incident);
                    activeIncident = null;
                } else {
                    // Retry with next step / alternative action
                    dispatchRemediationAction(incident, zScores, smoothed);
                }
            }
        }
    }

    public String determineAttributionVerdict(String target, double targetZ, Map<String, Double> zScores) {
        // 1. If acted on link (A -> B) and callee B has z >= 4.0 -> WRONG_TARGET (suspect callee)
        if (target != null && target.contains("->")) {
            String[] parts = target.split("->");
            String callee = parts.length > 1 ? parts[1].trim() : "";
            double calleeZ = computeComponentZ(callee, zScores);
            if (calleeZ >= 4.0) {
                return "WRONG_TARGET";
            }
        }

        // 2. If target z >= 4.0 and no other component > 1.5 * targetZ -> INEFFECTIVE_ACTION
        double maxOtherZ = findMaxZScoreExcluding(target, zScores);
        if (targetZ >= 4.0 && maxOtherZ <= (1.5 * targetZ)) {
            return "INEFFECTIVE_ACTION";
        }

        // 3. If target z < 2.5 and another component z >= 4.0 -> WRONG_TARGET
        if (targetZ < 2.5 && maxOtherZ >= 4.0) {
            return "WRONG_TARGET";
        }

        return "INCONCLUSIVE";
    }

    private double computeComponentZ(String component, Map<String, Double> zScores) {
        if (component == null || zScores == null) return 0.0;
        double max = 0.0;
        boolean isLink = component.contains("->");
        for (Map.Entry<String, Double> entry : zScores.entrySet()) {
            String key = entry.getKey();
            if (isLink) {
                if (key.startsWith("link:" + component + ":")) {
                    max = Math.max(max, entry.getValue());
                }
            } else {
                if (key.startsWith("node:" + component + ":") || key.startsWith("log:" + component + ":")) {
                    max = Math.max(max, entry.getValue());
                }
            }
        }
        return max;
    }

    private double findMaxZScore(Map<String, Double> zScores) {
        if (zScores == null || zScores.isEmpty()) return 0.0;
        return zScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    }

    private double findMaxZScoreExcluding(String target, Map<String, Double> zScores) {
        if (zScores == null || zScores.isEmpty()) return 0.0;
        double max = 0.0;
        boolean isLink = target != null && target.contains("->");
        for (Map.Entry<String, Double> entry : zScores.entrySet()) {
            String key = entry.getKey();
            boolean matchesTarget;
            if (isLink) {
                matchesTarget = key.startsWith("link:" + target + ":");
            } else {
                matchesTarget = key.startsWith("node:" + target + ":") || key.startsWith("log:" + target + ":");
            }
            if (!matchesTarget) {
                max = Math.max(max, entry.getValue());
            }
        }
        return max;
    }

    private String findMaxAbnormalService(Map<String, Double> zScores) {
        String best = "gateway";
        double max = -1.0;
        for (ServiceManager.ServiceDef def : serviceManager.getServiceDefs()) {
            double z = computeComponentZ(def.name(), zScores);
            if (z > max) {
                max = z;
                best = def.name();
            }
        }
        return best;
    }

    private void recordActionDb(String incidentId, Incident.IncidentActionRecord action) {
        jdbcTemplate.update(
                "INSERT INTO incident_actions (id, incident_id, step_num, action_type, target_component, started_at) VALUES (?, ?, ?, ?, ?, ?)",
                action.getId(), incidentId, action.getStepNum(), action.getActionType(), action.getTargetComponent(), action.getStartedAt()
        );
    }

    private void updateActionDb(Incident.IncidentActionRecord action) {
        jdbcTemplate.update(
                "UPDATE incident_actions SET completed_at = ?, post_action_z = ?, verdict = ? WHERE id = ?",
                action.getCompletedAt(), action.getPostActionZ(), action.getVerdict(), action.getId()
        );
    }

    private void updateIncidentDb(Incident incident, String status) {
        jdbcTemplate.update(
                "UPDATE incidents SET resolved_at = ?, root_cause_type = ?, target_entity = ?, final_status = ? WHERE id = ?",
                incident.getResolvedAt(), incident.getRootCauseType(), incident.getTargetEntity(), status, incident.getId()
        );
    }

    public Incident.OperatingMode getMode() { return mode; }
    public void setMode(Incident.OperatingMode mode) { this.mode = mode; }
    public Incident getActiveIncident() { return activeIncident; }
    public List<Incident> getIncidentHistory() { return Collections.unmodifiableList(incidentHistory); }
}
