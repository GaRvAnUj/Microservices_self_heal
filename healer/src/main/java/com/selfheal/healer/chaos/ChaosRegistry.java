package com.selfheal.healer.chaos;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central in-memory store of active link faults.
 *
 * INVARIANT: Link faults reside in the Healer's registry.
 * Polled by microservices every 1 s. Microservice restarts do not clear link faults.
 */
@Component
public class ChaosRegistry {

    public record LinkFaultEntry(String caller, String target, int delayMs, double lossPct) {}

    private final Map<String, LinkFaultEntry> activeFaults = new ConcurrentHashMap<>();

    public void setFault(String caller, String target, int delayMs, double lossPct) {
        String key = buildKey(caller, target);
        activeFaults.put(key, new LinkFaultEntry(caller.trim(), target.trim(), delayMs, lossPct));
    }

    public void removeFault(String caller, String target) {
        activeFaults.remove(buildKey(caller, target));
    }

    public void clearAll() {
        activeFaults.clear();
    }

    public LinkFaultEntry getFault(String caller, String target) {
        return activeFaults.get(buildKey(caller, target));
    }

    public Collection<LinkFaultEntry> getAllFaults() {
        return Collections.unmodifiableCollection(activeFaults.values());
    }

    public static String buildKey(String caller, String target) {
        return (caller != null ? caller.trim() : "") + "->" + (target != null ? target.trim() : "");
    }
}
