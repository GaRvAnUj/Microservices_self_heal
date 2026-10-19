package com.selfheal.healer.chaos;

import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

public class ChaosRegistryTest {

    @Test
    void testSetAndGetLinkFault() {
        ChaosRegistry registry = new ChaosRegistry();
        registry.setFault("compose-post", "text", 1200, 15.0);

        ChaosRegistry.LinkFaultEntry fault = registry.getFault("compose-post", "text");
        assertNotNull(fault);
        assertEquals("compose-post", fault.caller());
        assertEquals("text", fault.target());
        assertEquals(1200, fault.delayMs());
        assertEquals(15.0, fault.lossPct());
    }

    @Test
    void testRemoveAndClearAll() {
        ChaosRegistry registry = new ChaosRegistry();
        registry.setFault("compose-post", "text", 500, 0.0);
        registry.setFault("compose-post", "media", 300, 5.0);

        assertEquals(2, registry.getAllFaults().size());

        registry.removeFault("compose-post", "text");
        assertNull(registry.getFault("compose-post", "text"));
        assertNotNull(registry.getFault("compose-post", "media"));

        registry.clearAll();
        assertTrue(registry.getAllFaults().isEmpty());
    }
}
