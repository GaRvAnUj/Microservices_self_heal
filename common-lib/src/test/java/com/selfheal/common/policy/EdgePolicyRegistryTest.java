package com.selfheal.common.policy;

import com.selfheal.common.model.EdgePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class EdgePolicyRegistryTest {

    @Test
    void testDefaultPolicyValues() {
        EdgePolicyRegistry registry = new EdgePolicyRegistry();
        EdgePolicy policy = registry.getPolicy("post-storage");

        assertNotNull(policy);
        assertEquals(800, policy.getTimeoutMs());
        assertEquals(2, policy.getMaxRetries());
        assertEquals(20, policy.getRetryBudgetPct());
        assertFalse(policy.isBreakerOpen());
    }

    @Test
    void testUpdatePolicy() {
        EdgePolicyRegistry registry = new EdgePolicyRegistry();
        EdgePolicy updated = new EdgePolicy(300, 0, 10, true);

        registry.updatePolicy("text", updated);
        EdgePolicy retrieved = registry.getPolicy("text");

        assertEquals(300, retrieved.getTimeoutMs());
        assertEquals(0, retrieved.getMaxRetries());
        assertEquals(10, retrieved.getRetryBudgetPct());
        assertTrue(retrieved.isBreakerOpen());
    }
}
