package com.selfheal.healer.engine;

import com.selfheal.healer.collector.TelemetryCollectorService;
import com.selfheal.healer.db.DatabaseInitializer;
import com.selfheal.healer.load.LoadGenerator;
import com.selfheal.healer.service.ServiceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class EndToEndResilienceBenchmarkTest {

    private ServiceManager serviceManager;
    private LoadGenerator loadGenerator;
    private TelemetryCollectorService telemetryCollector;
    private StatisticalLocalizer localizer;
    private DiagnosisClient diagnosisClient;
    private PolicyService policyService;
    private JdbcTemplate jdbcTemplate;
    private HealingEngine healingEngine;

    @BeforeEach
    void setUp() {
        String dbName = "benchmarkdb_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer dbInit = new DatabaseInitializer(jdbcTemplate);
        dbInit.initialize();

        serviceManager = Mockito.mock(ServiceManager.class);
        loadGenerator = Mockito.mock(LoadGenerator.class);
        telemetryCollector = Mockito.mock(TelemetryCollectorService.class);

        localizer = new StatisticalLocalizer();
        // Use localizer directly for offline deterministic benchmark
        diagnosisClient = new DiagnosisClient(localizer, "http://127.0.0.1:59999/diagnose");
        policyService = new PolicyService(jdbcTemplate);

        when(serviceManager.getServiceDefs()).thenReturn(new ServiceManager().getServiceDefs());
        when(serviceManager.getServiceDef("compose-post")).thenReturn(new ServiceManager().getServiceDef("compose-post"));
        when(serviceManager.getServiceDef("text")).thenReturn(new ServiceManager().getServiceDef("text"));

        healingEngine = new HealingEngine(
                serviceManager, loadGenerator, telemetryCollector, diagnosisClient, policyService, jdbcTemplate
        );
        healingEngine.setMode(Incident.OperatingMode.PROPOSED);
    }

    @Test
    void testNovelty1And2_LinkFaultDiagnosisAndZeroDowntimeRemediation() {
        // 1. Initial State: SLO violated due to high link latency on (compose-post -> text)
        when(loadGenerator.getSloStatus()).thenReturn(
                new LoadGenerator.SloStatus(850.0, 320.0, 0.01, 70, false, 200)
        );

        // Callee (text) CPU is normal (z = 0.4), but link latency z-score is 6.8
        Map<String, Double> zScores = Map.of(
                "link:compose-post->text:avg_latency", 6.8,
                "node:text:cpu_process", 0.4,
                "node:compose-post:cpu_process", 0.6
        );
        when(telemetryCollector.getLatestZScores()).thenReturn(zScores);
        when(telemetryCollector.getLatestSmoothedSample()).thenReturn(Map.of("link:compose-post->text:avg_latency", 850.0));

        // 2. Evaluate Cycle 1: Triggers incident opening
        healingEngine.evaluateCycle();
        Incident incident = healingEngine.getActiveIncident();
        assertNotNull(incident, "Incident should be opened on SLO breach");
        assertEquals(Incident.Status.OPEN, incident.getStatus());

        // 3. Evaluate Cycle 2: Diagnoses (N1) and Dispatches Action (N2)
        healingEngine.evaluateCycle();
        assertEquals(Incident.Status.VERIFYING, incident.getStatus());
        assertEquals("compose-post->text", incident.getTargetEntity(), "N1 must diagnose pure link fault");
        assertEquals("link_delay", incident.getRootCauseType());

        // Verify N2: Link action was chosen from Q-policy without restarting compose-post
        assertEquals("tune_link", incident.getLatestAction().getActionType());
        verify(serviceManager, never()).restartService("compose-post");

        // 4. Verification Windows: System recovers under tuned link policy
        when(loadGenerator.getSloStatus()).thenReturn(
                new LoadGenerator.SloStatus(120.0, 35.0, 0.005, 70, true, 300)
        );
        Map<String, Double> recoveredZ = Map.of(
                "link:compose-post->text:avg_latency", 0.3,
                "node:text:cpu_process", 0.2
        );
        when(telemetryCollector.getLatestZScores()).thenReturn(recoveredZ);

        // Advance 3 verification windows
        healingEngine.evaluateCycle();
        healingEngine.evaluateCycle();
        healingEngine.evaluateCycle();

        // 5. Verify Resolution & Q-table Reward
        assertNull(healingEngine.getActiveIncident(), "Incident should be resolved");
        assertEquals(1, healingEngine.getIncidentHistory().size());
        Incident completed = healingEngine.getIncidentHistory().get(0);
        assertEquals(Incident.Status.RESOLVED, completed.getStatus());
        assertEquals("SUCCESS", completed.getLatestAction().getVerdict());

        // Verify Q-value was rewarded
        Double newQ = jdbcTemplate.queryForObject(
                "SELECT q_value FROM q_policy WHERE fault_type = 'link_delay' AND action_name = 'tune_link'",
                Double.class
        );
        assertNotNull(newQ);
        assertTrue(newQ > 0.90, "Q-value for tune_link must increase upon recovery success");
    }

    @Test
    void testNodeFaultDiagnosisAndProcessRestart() {
        // SLO violated due to CPU hog on 'text' node
        when(loadGenerator.getSloStatus()).thenReturn(
                new LoadGenerator.SloStatus(950.0, 500.0, 0.04, 70, false, 150)
        );
        Map<String, Double> zScores = Map.of(
                "node:text:cpu_process", 8.2,
                "node:compose-post:cpu_process", 0.5
        );
        when(telemetryCollector.getLatestZScores()).thenReturn(zScores);
        when(telemetryCollector.getLatestSmoothedSample()).thenReturn(Map.of("node:text:cpu_process", 0.98));

        healingEngine.evaluateCycle();
        healingEngine.evaluateCycle();

        Incident incident = healingEngine.getActiveIncident();
        assertNotNull(incident);
        assertEquals("text", incident.getTargetEntity());
        assertEquals("cpu_hog", incident.getRootCauseType());
        assertEquals("restart", incident.getLatestAction().getActionType());

        // Process restart MUST be called on the faulty node
        verify(serviceManager, times(1)).restartService("text");
    }

    @Test
    void testNovelty3_IneffectiveActionAttributionAndQPenalty() {
        // Initial Q for (link_delay, tune_link) is 0.9
        Double initialQ = jdbcTemplate.queryForObject(
                "SELECT q_value FROM q_policy WHERE fault_type = 'link_delay' AND action_name = 'tune_link'",
                Double.class
        );
        assertEquals(0.9, initialQ, 0.001);

        // Link delay fault
        when(loadGenerator.getSloStatus()).thenReturn(
                new LoadGenerator.SloStatus(750.0, 300.0, 0.02, 70, false, 100)
        );
        Map<String, Double> zScores = Map.of(
                "link:compose-post->text:avg_latency", 6.0,
                "node:text:cpu_process", 0.2
        );
        when(telemetryCollector.getLatestZScores()).thenReturn(zScores);

        // Open incident and dispatch action
        healingEngine.evaluateCycle();
        healingEngine.evaluateCycle();

        Incident incident = healingEngine.getActiveIncident();
        assertNotNull(incident);

        // Post-action verification: SLO STILL VIOLATED and link is STILL abnormal (z = 5.5)
        when(loadGenerator.getSloStatus()).thenReturn(
                new LoadGenerator.SloStatus(800.0, 320.0, 0.02, 70, false, 150)
        );
        Map<String, Double> stillBadZ = Map.of(
                "link:compose-post->text:avg_latency", 5.5,
                "node:text:cpu_process", 0.2
        );
        when(telemetryCollector.getLatestZScores()).thenReturn(stillBadZ);

        // Advance verification windows
        healingEngine.evaluateCycle();
        healingEngine.evaluateCycle();
        healingEngine.evaluateCycle();

        // N3: Ineffective action must penalize Q-value: 0.9 * 0.3 = 0.27
        Double penalizedQ = jdbcTemplate.queryForObject(
                "SELECT q_value FROM q_policy WHERE fault_type = 'link_delay' AND action_name = 'tune_link'",
                Double.class
        );
        assertNotNull(penalizedQ);
        assertEquals(0.27, penalizedQ, 0.001);
    }
}
