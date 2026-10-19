package com.selfheal.common.policy;

import com.selfheal.common.model.EdgePolicy;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory registry of EdgePolicy settings per outgoing target service.
 */
public class EdgePolicyRegistry {
    private final Map<String, EdgePolicy> policies = new ConcurrentHashMap<>();

    public EdgePolicy getPolicy(String targetService) {
        return policies.computeIfAbsent(targetService, k -> new EdgePolicy());
    }

    public void updatePolicy(String targetService, EdgePolicy updated) {
        policies.put(targetService, new EdgePolicy(updated));
    }

    public Map<String, EdgePolicy> getAllPolicies() {
        return Collections.unmodifiableMap(policies);
    }
}
