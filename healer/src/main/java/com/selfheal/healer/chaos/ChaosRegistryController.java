package com.selfheal.healer.chaos;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

/**
 * Controller exposing endpoints for chaos injection and registry polling.
 * Bound to 127.0.0.1.
 */
@RestController
public class ChaosRegistryController {

    private final ChaosRegistry chaosRegistry;

    public ChaosRegistryController(ChaosRegistry chaosRegistry) {
        this.chaosRegistry = chaosRegistry;
    }

    /**
     * Polled by LinkClient in each microservice every 1s.
     */
    @GetMapping("/chaos/registry")
    public Collection<ChaosRegistry.LinkFaultEntry> getRegistry() {
        return chaosRegistry.getAllFaults();
    }

    /**
     * Injects or updates a link fault.
     */
    @PutMapping("/chaos/link")
    public ResponseEntity<Map<String, Object>> setLinkFault(
            @RequestParam("caller") String caller,
            @RequestParam("target") String target,
            @RequestParam(value = "delayMs", defaultValue = "0") int delayMs,
            @RequestParam(value = "lossPct", defaultValue = "0.0") double lossPct) {

        chaosRegistry.setFault(caller, target, delayMs, lossPct);
        return ResponseEntity.ok(Map.of(
                "status", "INJECTED",
                "caller", caller,
                "target", target,
                "delayMs", delayMs,
                "lossPct", lossPct
        ));
    }

    /**
     * Clears a specific link fault.
     */
    @DeleteMapping("/chaos/link")
    public ResponseEntity<Map<String, String>> clearLinkFault(
            @RequestParam("caller") String caller,
            @RequestParam("target") String target) {

        chaosRegistry.removeFault(caller, target);
        return ResponseEntity.ok(Map.of(
                "status", "CLEARED",
                "link", caller + "->" + target
        ));
    }

    /**
     * Clears all active link faults.
     */
    @DeleteMapping("/chaos/all")
    public ResponseEntity<Map<String, String>> clearAllFaults() {
        chaosRegistry.clearAll();
        return ResponseEntity.ok(Map.of("status", "ALL_CLEARED"));
    }
}
