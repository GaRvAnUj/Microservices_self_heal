package com.selfheal.common.client;

import com.selfheal.common.chaos.ChaosRegistryCache;
import com.selfheal.common.model.EdgePolicy;
import com.selfheal.common.model.LinkFault;
import com.selfheal.common.policy.EdgePolicyRegistry;
import com.selfheal.common.telemetry.LogEventTracker;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class LinkClientTest {

    @Test
    void testCircuitBreakerOpenThrowsFast() {
        EdgePolicyRegistry registry = new EdgePolicyRegistry();
        EdgePolicy breakerOpenPolicy = new EdgePolicy(800, 2, 20, true);
        registry.updatePolicy("text", breakerOpenPolicy);

        ChaosRegistryCache chaosCache = new ChaosRegistryCache();
        LogEventTracker logTracker = new LogEventTracker();

        LinkClient client = new LinkClient("compose-post", registry, chaosCache, logTracker);

        assertThrows(CircuitBreakerOpenException.class, () ->
                client.call("text", "http://localhost:8083/work", "GET", null)
        );

        assertEquals(1, logTracker.getCount(LogEventTracker.DOWNSTREAM_ERROR));
        assertEquals(1, client.getOrCreateStats("text").getCallCount());
        assertEquals(1, client.getOrCreateStats("text").getErrorCount());
    }

    @Test
    void testChaosLinkLossSimulated() {
        EdgePolicyRegistry registry = new EdgePolicyRegistry();
        ChaosRegistryCache chaosCache = new ChaosRegistryCache();
        // 100% loss
        chaosCache.setLinkFaultLocal("compose-post", "media", new LinkFault("compose-post", "media", 0, 100.0));
        LogEventTracker logTracker = new LogEventTracker();

        LinkClient client = new LinkClient("compose-post", registry, chaosCache, logTracker);

        assertThrows(IOException.class, () ->
                client.call("media", "http://localhost:8084/work", "GET", null)
        );

        assertTrue(logTracker.getCount(LogEventTracker.DOWNSTREAM_ERROR) >= 1);
        assertEquals(1, client.getOrCreateStats("media").getErrorCount());
    }
}
