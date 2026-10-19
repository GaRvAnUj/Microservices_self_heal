package com.selfheal.healer.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ServiceManagerTest {

    @Test
    void testServiceDefinitionsAndDefaults() {
        ServiceManager sm = new ServiceManager();
        List<ServiceManager.ServiceDef> defs = sm.getServiceDefs();
        assertEquals(6, defs.size());

        ServiceManager.ServiceDef postStorage = sm.getServiceDef("post-storage");
        assertNotNull(postStorage);
        assertEquals(8086, postStorage.port());
        assertTrue(postStorage.critical(), "post-storage must be marked critical");

        ServiceManager.ServiceDef compose = sm.getServiceDef("compose-post");
        assertNotNull(compose);
        assertEquals(8082, compose.port());
        assertFalse(compose.critical());
        assertNotNull(compose.downstream());
        assertTrue(compose.downstream().contains("text:8083"));

        assertNull(sm.getServiceDef("nonexistent-service"));
    }

    @Test
    void testServiceStatusTracking() {
        ServiceManager.ServiceStatus status = new ServiceManager.ServiceStatus("text", 8083);
        assertEquals("DOWN", status.getState());
        assertEquals(0, status.getRestartCount());
        assertEquals(0, status.getTotalDowntimeMs());
        assertNull(status.getPid());

        status.markStarted(4242L);
        assertEquals("UP", status.getState());
        assertEquals(4242L, status.getPid());

        status.markRestarting();
        assertEquals("RESTARTING", status.getState());
        assertEquals(1, status.getRestartCount());

        status.addDowntime(1250);
        assertEquals(1250, status.getTotalDowntimeMs());

        status.markDown();
        assertEquals("DOWN", status.getState());
        assertNull(status.getPid());
    }

    @Test
    void testInitialStatusesMap() {
        ServiceManager sm = new ServiceManager();
        Map<String, ServiceManager.ServiceStatus> statuses = sm.getStatuses();
        assertEquals(6, statuses.size());
        assertTrue(statuses.containsKey("gateway"));
        assertTrue(statuses.containsKey("compose-post"));
        assertTrue(statuses.containsKey("text"));
        assertTrue(statuses.containsKey("media"));
        assertTrue(statuses.containsKey("user"));
        assertTrue(statuses.containsKey("post-storage"));
    }
}
