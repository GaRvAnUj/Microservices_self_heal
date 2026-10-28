package com.selfheal.healer.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IncidentTest {

    @Test
    void actionRecordRequiresARealTarget() {
        assertThrows(IllegalArgumentException.class, () ->
                new Incident.IncidentActionRecord("id", 1, "restart", null, 0, "crash", null));
        assertThrows(IllegalArgumentException.class, () ->
                new Incident.IncidentActionRecord("id", 1, "restart", "null", 0, "crash", null));
        assertDoesNotThrow(() ->
                new Incident.IncidentActionRecord("id", 1, "restart", "compose-post", 0, "crash", null));
    }

    @Test
    void diagnosisRequiresARealCandidate() {
        Incident incident = new Incident("id", 0, "test", Incident.OperatingMode.PROPOSED);

        assertThrows(IllegalArgumentException.class, () -> incident.setDiagnosis(null, "crash"));
        assertThrows(IllegalArgumentException.class, () -> incident.setDiagnosis("null", "crash"));
        assertDoesNotThrow(() -> incident.setDiagnosis("compose-post", "crash"));
    }
}
