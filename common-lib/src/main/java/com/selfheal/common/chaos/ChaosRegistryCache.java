package com.selfheal.common.chaos;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfheal.common.model.LinkFault;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodically polls the Healer's centralized Chaos Registry.
 *
 * INVARIANT: Link faults live in the Healer's registry.
 * Local service restarts MUST NOT clear link faults.
 */
public class ChaosRegistryCache {
    private static final Logger log = Logger.getLogger(ChaosRegistryCache.class.getName());
    private static final String DEFAULT_REGISTRY_URL = "http://localhost:8080/chaos/registry";

    private final String registryUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, LinkFault> cachedFaults = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    public ChaosRegistryCache() {
        this(DEFAULT_REGISTRY_URL);
    }

    public ChaosRegistryCache(String registryUrl) {
        this.registryUrl = registryUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public synchronized void start() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chaos-registry-poller");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::pollRegistry, 0, 1, TimeUnit.SECONDS);
        }
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public void pollRegistry() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(registryUrl))
                    .timeout(Duration.ofMillis(800))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body() != null && !response.body().isBlank()) {
                updateCache(response.body());
            }
        } catch (Exception e) {
            // Healer may not be running yet; keep current cache without failing
            log.log(Level.FINE, "Could not poll Healer chaos registry: {0}", e.getMessage());
        }
    }

    void updateCache(String json) {
        try {
            // Support both Map<String, LinkFault> and List<LinkFault>
            if (json.trim().startsWith("[")) {
                List<LinkFault> list = objectMapper.readValue(json, new TypeReference<List<LinkFault>>() {});
                cachedFaults.clear();
                for (LinkFault fault : list) {
                    cachedFaults.put(buildKey(fault.getCaller(), fault.getTarget()), fault);
                }
            } else {
                Map<String, LinkFault> map = objectMapper.readValue(json, new TypeReference<Map<String, LinkFault>>() {});
                cachedFaults.clear();
                cachedFaults.putAll(map);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to parse chaos registry payload: {0}", e.getMessage());
        }
    }

    public LinkFault getLinkFault(String caller, String target) {
        return cachedFaults.get(buildKey(caller, target));
    }

    public void setLinkFaultLocal(String caller, String target, LinkFault fault) {
        // Used in unit tests
        if (fault == null) {
            cachedFaults.remove(buildKey(caller, target));
        } else {
            cachedFaults.put(buildKey(caller, target), fault);
        }
    }

    public Map<String, LinkFault> getAllCachedFaults() {
        return Collections.unmodifiableMap(cachedFaults);
    }

    public static String buildKey(String caller, String target) {
        return (caller != null ? caller.trim() : "") + "->" + (target != null ? target.trim() : "");
    }
}
