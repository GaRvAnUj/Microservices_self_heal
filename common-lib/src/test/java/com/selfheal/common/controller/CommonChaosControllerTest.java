package com.selfheal.common.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommonChaosControllerTest {

    private final CommonChaosController controller = new CommonChaosController(
            null, null, null, null
    );

    @Test
    void cpuHogRejectsThreadCountsOutsideAvailableProcessors() {
        ResponseEntity<Map<String, Object>> response = controller.toggleCpuHog(
                true, Runtime.getRuntime().availableProcessors() + 1
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void cpuHogUsesRequestedThreadCountAndCanBeStopped() {
        ResponseEntity<Map<String, Object>> started = controller.toggleCpuHog(true, 1);
        ResponseEntity<Map<String, Object>> stopped = controller.toggleCpuHog(false, 1);

        assertEquals(HttpStatus.OK, started.getStatusCode());
        assertEquals(1, started.getBody().get("threads"));
        assertEquals(HttpStatus.OK, stopped.getStatusCode());
    }
}
