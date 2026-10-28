package com.selfheal.healer.engine;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DiagnosisClientTest {

    @Test
    void testGracefulFallbackWhenPythonServiceUnavailable() {
        StatisticalLocalizer localizer = new StatisticalLocalizer();
        // Point to an inactive port to simulate service unreachable
        DiagnosisClient client = new DiagnosisClient(localizer, "http://127.0.0.1:59999/diagnose");

        Map<String, Double> zScores = Map.of(
                "link:compose-post->text:avg_latency", 6.2,
                "node:text:cpu_process", 0.5
        );

        DiagnosisClient.DiagnosisResult result = client.diagnose(zScores, Map.of());

        assertNotNull(result);
        assertTrue(result.anomalyDetected());
        assertEquals("compose-post->text", result.targetEntity());
        assertEquals("link_delay", result.faultType());
        assertTrue(result.isLinkFault());
        // Verify scientific integrity tag
        assertEquals("JAVA_STATISTICAL_APPROXIMATION", result.diagnosisSource());
    }

    @Test
    void testFallbackSourceModeUsesJavaWithoutCallingPython() {
        DiagnosisClient client = new DiagnosisClient(
                new StatisticalLocalizer(), "http://127.0.0.1:59999/diagnose"
        );
        client.setSourceMode("fallback");

        DiagnosisClient.DiagnosisResult result = client.diagnose(
                Map.of("node:text:cpu_process", 5.0),
                Map.of("node:text:cpu_process", 0.9)
        );

        assertEquals("text", result.targetEntity());
        assertEquals("JAVA_STATISTICAL_APPROXIMATION", result.diagnosisSource());
        assertEquals(1L, ((Number) client.getLatencyMetrics().get("fallback_count")).longValue());
    }

    @Test
    void testDiagnosisSourceReturnedByPythonServiceIsPreserved() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/diagnose", exchange -> {
            byte[] body = """
                    {"anomalyDetected":true,"targetEntity":"text","faultType":"cpu_hog",
                     "isLinkFault":false,"confidence":0.91,"diagnosisSource":"MODIFy"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/diagnose";
            DiagnosisClient client = new DiagnosisClient(new StatisticalLocalizer(), endpoint);

            DiagnosisClient.DiagnosisResult result = client.diagnose(
                    Map.of("node:text:cpu_process", 5.0),
                    Map.of("node:text:cpu_process", 0.9)
            );

            assertEquals("MODIFy", result.diagnosisSource());
            assertEquals("text", result.targetEntity());
            assertEquals("cpu_hog", result.faultType());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testNullPythonCandidateUsesJavaStatisticalFallback() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/diagnose", exchange -> {
            byte[] body = """
                    {"anomalyDetected":true,"targetEntity":null,"faultType":null,
                     "isLinkFault":false,"confidence":0.0,"diagnosisSource":"MODIFy"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/diagnose";
            DiagnosisClient client = new DiagnosisClient(new StatisticalLocalizer(), endpoint);

            DiagnosisClient.DiagnosisResult result = client.diagnose(
                    Map.of(
                            "link:compose-post->text:avg_latency", 6.2,
                            "node:text:cpu_process", 0.5
                    ),
                    Map.of()
            );

            assertTrue(result.anomalyDetected());
            assertEquals("compose-post->text", result.targetEntity());
            assertEquals("link_delay", result.faultType());
            assertEquals("JAVA_STATISTICAL_APPROXIMATION", result.diagnosisSource());
        } finally {
            server.stop(0);
        }
    }
}
