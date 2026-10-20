package com.selfheal.healer.controller;

import com.selfheal.healer.chaos.ChaosRegistry;
import com.selfheal.healer.collector.TelemetryCollectorService;
import com.selfheal.healer.engine.HealingEngine;
import com.selfheal.healer.engine.Incident;
import com.selfheal.healer.engine.PolicyService;
import com.selfheal.healer.load.LoadGenerator;
import com.selfheal.healer.service.ServiceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

public class DashboardApiControllerTest {

    private ServiceManager serviceManager;
    private LoadGenerator loadGenerator;
    private TelemetryCollectorService telemetryCollector;
    private HealingEngine healingEngine;
    private PolicyService policyService;
    private ChaosRegistry chaosRegistry;
    private DashboardApiController controller;

    @BeforeEach
    void setUp() {
        serviceManager = Mockito.mock(ServiceManager.class);
        loadGenerator = Mockito.mock(LoadGenerator.class);
        telemetryCollector = Mockito.mock(TelemetryCollectorService.class);
        healingEngine = Mockito.mock(HealingEngine.class);
        policyService = Mockito.mock(PolicyService.class);
        chaosRegistry = new ChaosRegistry();

        when(healingEngine.getMode()).thenReturn(Incident.OperatingMode.PROPOSED);
        when(loadGenerator.getSloStatus()).thenReturn(new LoadGenerator.SloStatus(120.0, 45.0, 0.01, 70, true, 500));
        when(serviceManager.getStatuses()).thenReturn(Map.of());
        when(policyService.getAllQEntries()).thenReturn(List.of());
        when(telemetryCollector.getLatestZScores()).thenReturn(Map.of());
        when(telemetryCollector.getLatestSmoothedSample()).thenReturn(Map.of());

        controller = new DashboardApiController(
                serviceManager, loadGenerator, telemetryCollector, healingEngine, policyService, chaosRegistry
        );
    }

    @Test
    void testGetStatusSnapshot() {
        Map<String, Object> status = controller.getStatus();
        assertNotNull(status);
        assertEquals("PROPOSED", status.get("mode"));
        assertTrue(status.containsKey("slo"));
        assertTrue(status.containsKey("services"));
        assertTrue(status.containsKey("chaosFaults"));
        assertTrue(status.containsKey("qPolicy"));
    }

    @Test
    void testSetMode() {
        ResponseEntity<Map<String, String>> respProposed = controller.setMode("PROPOSED");
        assertEquals(200, respProposed.getStatusCode().value());
        assertEquals("PROPOSED", respProposed.getBody().get("mode"));

        ResponseEntity<Map<String, String>> respBaseline = controller.setMode("BASELINE");
        assertEquals(200, respBaseline.getStatusCode().value());
        assertEquals("BASELINE", respBaseline.getBody().get("mode"));

        ResponseEntity<Map<String, String>> respInvalid = controller.setMode("INVALID_MODE");
        assertEquals(400, respInvalid.getStatusCode().value());
    }

    @Test
    void testInjectAndClearChaos() {
        ResponseEntity<Map<String, Object>> resp = controller.injectChaos(
                "link_delay", "compose-post", "text", 1500, 20.0
        );
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("INJECTED", resp.getBody().get("status"));

        ChaosRegistry.LinkFaultEntry fault = chaosRegistry.getFault("compose-post", "text");
        assertNotNull(fault);
        assertEquals(1500, fault.delayMs());
        assertEquals(20.0, fault.lossPct());

        // Clear all chaos
        ResponseEntity<Map<String, String>> clearResp = controller.clearAllChaos();
        assertEquals(200, clearResp.getStatusCode().value());
        assertTrue(chaosRegistry.getAllFaults().isEmpty());
    }
}
