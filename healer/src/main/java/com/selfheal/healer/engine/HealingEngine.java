package com.selfheal.healer.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfheal.healer.collector.TelemetryCollectorService;
import com.selfheal.healer.load.LoadGenerator;
import com.selfheal.healer.service.ServiceManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.dao.DataAccessException;
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
    public static final double ANOMALY_Z_THRESHOLD = 4.0;
    public static final double ATTRIBUTION_LOW_Z_THRESHOLD = 2.5;
    public static final double ATTRIBUTION_DOMINANCE_RATIO = 1.5;

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
    private volatile boolean experimentPaused;
    private volatile boolean plantedWrongTarget;
    private volatile boolean wrongTargetPlanted;
    private volatile String expectedFaultTarget;
    private volatile String expectedFaultType;
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
        if (experimentPaused) {
            return;
        }
        try {
            LoadGenerator.SloStatus slo = loadGenerator.getSloStatus();
            Map<String, Double> zScores = telemetryCollector.getLatestZScores();
            Map<String, Double> smoothed = telemetryCollector.getLatestSmoothedSample();

            double maxZ = findMaxZScore(zScores);
            if (maxZ >= ANOMALY_Z_THRESHOLD) {
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

    public synchronized void prepareExperiment(Incident.OperatingMode experimentMode, boolean plantWrongTarget) {
        prepareExperiment(experimentMode, plantWrongTarget, null, null, "auto");
    }

    public synchronized void prepareExperiment(Incident.OperatingMode experimentMode,
                                               boolean plantWrongTarget,
                                               String expectedFaultTarget,
                                               String expectedFaultType) {
        prepareExperiment(experimentMode, plantWrongTarget, expectedFaultTarget, expectedFaultType, "auto");
    }

    public synchronized void prepareExperiment(Incident.OperatingMode experimentMode,
                                               boolean plantWrongTarget,
                                               String expectedFaultTarget,
                                               String expectedFaultType,
                                               String diagnosisSourceMode) {
        experimentPaused = true;
        if (activeIncident != null) {
            activeIncident.escalate();
            updateIncidentDb(activeIncident, "ESCALATED");
            activeIncident = null;
        }
        incidentHistory.clear();
        consecutiveAnomalySamples = 0;
        mode = experimentMode;
        plantedWrongTarget = plantWrongTarget;
        wrongTargetPlanted = false;
        this.expectedFaultTarget = Incident.isValidTarget(expectedFaultTarget)
                ? expectedFaultTarget.trim()
                : null;
        this.expectedFaultType = expectedFaultType == null || expectedFaultType.isBlank()
                ? null
                : expectedFaultType.trim();
        diagnosisClient.setSourceMode(diagnosisSourceMode);
    }

    public synchronized void startExperiment() {
        consecutiveAnomalySamples = 0;
        experimentPaused = false;
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
                if (incident.getMode() == Incident.OperatingMode.NO_HEAL) {
                    diagnoseWithoutHealing(incident, zScores, smoothed);
                } else {
                    dispatchRemediationAction(incident, zScores, smoothed);
                }
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

    private void diagnoseWithoutHealing(Incident incident,
                                        Map<String, Double> zScores,
                                        Map<String, Double> smoothed) {
        DiagnosisClient.DiagnosisResult diagnosis = diagnosisClient.diagnose(zScores, smoothed, true);
        String target = diagnosis.targetEntity();
        String faultType = diagnosis.faultType();
        if (!Incident.isValidTarget(target)) {
            target = findMaxAbnormalService(zScores);
            if (faultType == null || faultType.isBlank() || "null".equalsIgnoreCase(faultType)) {
                faultType = "cpu_hog";
            }
        }
        if (!Incident.isValidTarget(target)) {
            log.warning("NO_HEAL incident has no valid diagnosis candidate.");
            return;
        }
        incident.setDiagnosis(target, faultType, diagnosis.diagnosisSource());
        log.info(String.format("NO_HEAL diagnosis: candidate=%s fault=%s source=%s",
                target, faultType, diagnosis.diagnosisSource()));
    }

    private void dispatchRemediationAction(Incident incident,
                                           Map<String, Double> zScores,
                                           Map<String, Double> smoothed) {
        int stepNum = incident.getActions().size() + 1;
        String actionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        if (incident.getMode() == Incident.OperatingMode.BASELINE) {
            // BASELINE: Blindly restart service with highest abnormality
            String targetService = maybePlantWrongTarget(findMaxAbnormalService(zScores));
            if (!Incident.isValidTarget(targetService)) {
                failUndiagnosableIncident(incident, "No service candidate was available for BASELINE.");
                return;
            }
            incident.setDiagnosis(targetService, "unknown_node_fault", "NA");

            Incident.IncidentActionRecord action = new Incident.IncidentActionRecord(
                    actionId, stepNum, "restart", targetService, now, "unknown_node_fault", null
            );
            incident.addAction(action);
            recordActionDb(incident.getId(), action);

            log.info(String.format("BASELINE action dispatch: restarting process %s", targetService));
            serviceManager.restartService(targetService);
            incident.startVerification(3);

        } else {
            boolean allowLinkCandidates = incident.getMode() != Incident.OperatingMode.PROPOSED_NO_LINK;
            Incident.IncidentActionRecord previousAction = incident.getLatestAction();
            boolean retryIneffectiveAction = incident.getMode() == Incident.OperatingMode.PROPOSED
                    && previousAction != null
                    && "INEFFECTIVE_ACTION".equals(previousAction.getVerdict());
            String target;
            String faultType;
            String diagnosisSource;
            String actionType;

            if (retryIneffectiveAction) {
                target = incident.getTargetEntity();
                faultType = incident.getRootCauseType();
                diagnosisSource = incident.getDiagnosisSource();
                actionType = policyService.getAlternativeAction(faultType, previousAction.getActionType());
            } else {
                Set<String> excludedTargets = previouslyWrongTargets(incident);
                DiagnosisClient.DiagnosisResult diagnosis = diagnosisClient.diagnose(
                        zScores, smoothed, allowLinkCandidates, excludedTargets
                );
                target = diagnosis.targetEntity();
                faultType = diagnosis.faultType();
                diagnosisSource = diagnosis.diagnosisSource();
                if (!Incident.isValidTarget(target)) {
                    target = findMaxAbnormalService(zScores, excludedTargets);
                    if (faultType == null || faultType.isBlank() || "null".equalsIgnoreCase(faultType)) {
                        faultType = "cpu_hog";
                    }
                }
                if (stepNum == 1) {
                    target = maybePlantWrongTarget(target);
                }
                actionType = null;
            }
            if (!Incident.isValidTarget(target)) {
                failUndiagnosableIncident(incident, "Diagnosis and statistical fallback returned no candidate.");
                return;
            }
            if (actionType == null) {
                actionType = policyService.getBestAction(faultType);
            }
            if (!retryIneffectiveAction) {
                incident.setDiagnosis(target, faultType, diagnosisSource);
            }

            Double qBefore = policyService.getQValue(faultType, actionType);
            String actionTarget = target;
            if (!allowLinkCandidates && isLinkFaultType(faultType)) {
                actionTarget = selectTopologyLinkForService(target);
            }
            Incident.IncidentActionRecord action = new Incident.IncidentActionRecord(
                    actionId, stepNum, actionType, actionTarget, now, faultType, qBefore
            );
            incident.addAction(action);
            recordActionDb(incident.getId(), action);
            if (stepNum > 1) {
                incident.getActions().get(stepNum - 2).setSuspectAfter(target);
            }

            log.info(String.format("PROPOSED action dispatch: [%s] on target [%s] for [%s] (source: %s)",
                    actionType, actionTarget, faultType, diagnosisSource));

            executeProposedAction(actionTarget, faultType, actionType);
            incident.startVerification(3);
        }
    }

    private Set<String> previouslyWrongTargets(Incident incident) {
        Set<String> excludedTargets = new HashSet<>();
        for (Incident.IncidentActionRecord action : incident.getActions()) {
            if ("WRONG_TARGET".equals(action.getVerdict())) {
                excludedTargets.add(action.getTargetComponent());
            }
        }
        return excludedTargets;
    }

    private static boolean isLinkFaultType(String faultType) {
        return "link_delay".equals(faultType) || "link_loss".equals(faultType);
    }

    private String maybePlantWrongTarget(String target) {
        if (!plantedWrongTarget || wrongTargetPlanted) {
            return target;
        }
        String neighbor = serviceManager.getNeighborComponent(target);
        if (!Incident.isValidTarget(neighbor) || neighbor.equals(target)) {
            log.severe("Unable to plant a distinct, valid wrong target for " + target);
            return null;
        }
        wrongTargetPlanted = true;
        log.info(String.format("Experiment planted wrong target: %s -> %s", target, neighbor));
        return neighbor;
    }

    private String selectTopologyLinkForService(String service) {
        ServiceManager.ServiceDef serviceDef = serviceManager.getServiceDef(service);
        if (serviceDef != null && serviceDef.downstream() != null && !serviceDef.downstream().isBlank()) {
            String child = serviceDef.downstream().split(",")[0].split(":")[0].trim();
            return service + "->" + child;
        }
        for (ServiceManager.ServiceDef def : serviceManager.getServiceDefs()) {
            if (def.downstream() != null) {
                for (String downstream : def.downstream().split(",")) {
                    if (downstream.split(":")[0].trim().equalsIgnoreCase(service)) {
                        return def.name() + "->" + service;
                    }
                }
            }
        }
        return service;
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
                String jsonBody = String.format("{\"timeoutMs\":%d,\"maxRetries\":%d,\"retryBudgetPct\":%d,\"breakerOpen\":%b}",
                        timeoutMs, maxRetries, budget, breakerOpen);
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofMillis(800))
                            .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
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

        String actionTarget = latest == null ? target : latest.getTargetComponent();
        double postActionZ = computeComponentZ(actionTarget, zScores);

        if (slo.sloHealthy()) {
            // SUCCESS
            if (latest != null) {
                latest.complete(now, postActionZ, "SUCCESS");
            }
            incident.resolve(now);
            updateIncidentDb(incident, "RESOLVED");

            if (incident.getMode() != Incident.OperatingMode.BASELINE && latest != null) {
                policyService.rewardAction(faultType, latest.getActionType());
                latest.setQAfter(policyService.getQValue(faultType, latest.getActionType()));
            }
            boolean recoveredAfterWrongTarget = incident.getActions().stream()
                    .anyMatch(action -> "WRONG_TARGET".equals(action.getVerdict()));
            if (incident.getMode() == Incident.OperatingMode.PROPOSED && recoveredAfterWrongTarget) {
                if (!Incident.isValidTarget(expectedFaultTarget) || expectedFaultType == null) {
                    log.warning("Cannot save labelled sample after WRONG_TARGET: injected ground truth was not provided.");
                } else {
                    try {
                        String featuresJson = mapper.writeValueAsString(smoothed);
                        policyService.saveLabelledSample(expectedFaultTarget, expectedFaultType, featuresJson);
                    } catch (JsonProcessingException e) {
                        log.log(Level.SEVERE, "Unable to serialize telemetry for labelled sample.", e);
                    } catch (DataAccessException e) {
                        log.log(Level.SEVERE, "Unable to persist labelled sample after confirmed recovery.", e);
                    }
                }
            }
            if (latest != null) {
                updateActionDb(latest);
            }

            log.info(String.format("INCIDENT RESOLVED [%s] in %d ms!", incident.getId(), (now - incident.getStartedAt())));
            incidentHistory.add(incident);
            activeIncident = null;

        } else {
            // REMEDIATION FAILED: perform N3 Attribution if in PROPOSED mode
            if (incident.getMode() == Incident.OperatingMode.BASELINE) {
                if (latest != null) {
                    latest.complete(now, postActionZ, "NA");
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
                boolean attributionEnabled = incident.getMode() == Incident.OperatingMode.PROPOSED;
                String verdict = attributionEnabled
                        ? determineAttributionVerdict(actionTarget, postActionZ, zScores)
                        : "NA";
                if (latest != null) {
                    latest.complete(now, postActionZ, verdict);
                }

                log.warning(String.format("Remediation verification failed. %s: %s (postActionZ=%.2f)",
                        attributionEnabled ? "N3 Attribution" : "Uniform failure feedback", verdict, postActionZ));

                if (latest != null && (!attributionEnabled || "INEFFECTIVE_ACTION".equals(verdict))) {
                    policyService.penalizeAction(faultType, latest.getActionType());
                }
                if (latest != null) {
                    latest.setQAfter(policyService.getQValue(faultType, latest.getActionType()));
                    updateActionDb(latest);
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
        // 1. If acted on link (A -> B) and callee B has z >= threshold -> WRONG_TARGET
        if (target != null && target.contains("->")) {
            String[] parts = target.split("->");
            String callee = parts.length > 1 ? parts[1].trim() : "";
            double calleeZ = computeComponentZ(callee, zScores);
            if (calleeZ >= ANOMALY_Z_THRESHOLD) {
                return "WRONG_TARGET";
            }
        }

        // 2. If target z >= threshold and no other component exceeds the dominance ratio
        double maxOtherZ = findMaxZScoreExcluding(target, zScores);
        if (targetZ >= ANOMALY_Z_THRESHOLD && maxOtherZ <= (ATTRIBUTION_DOMINANCE_RATIO * targetZ)) {
            return "INEFFECTIVE_ACTION";
        }

        // 3. If target z is low and another component exceeds the anomaly threshold -> WRONG_TARGET
        if (targetZ < ATTRIBUTION_LOW_Z_THRESHOLD && maxOtherZ >= ANOMALY_Z_THRESHOLD) {
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
        return findMaxAbnormalService(zScores, Set.of());
    }

    private String findMaxAbnormalService(Map<String, Double> zScores, Set<String> excludedTargets) {
        if (zScores == null || zScores.isEmpty()) {
            return null;
        }
        Set<String> excluded = excludedTargets == null ? Set.of() : excludedTargets;
        String best = null;
        double max = Double.NEGATIVE_INFINITY;
        for (ServiceManager.ServiceDef def : serviceManager.getServiceDefs()) {
            if (excluded.contains(def.name())) {
                continue;
            }
            boolean hasNodeSignals = zScores.keySet().stream().anyMatch(key ->
                    key.startsWith("node:" + def.name() + ":")
                            || key.startsWith("log:" + def.name() + ":"));
            if (!hasNodeSignals) {
                continue;
            }
            double z = computeComponentZ(def.name(), zScores);
            if (z > max) {
                max = z;
                best = def.name();
            }
        }
        return best;
    }

    private void failUndiagnosableIncident(Incident incident, String reason) {
        log.severe(String.format("Incident [%s] escalated without dispatch: %s", incident.getId(), reason));
        incident.escalate();
        updateIncidentDb(incident, "ESCALATED");
        incidentHistory.add(incident);
        activeIncident = null;
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
