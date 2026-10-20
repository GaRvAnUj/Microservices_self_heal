package com.selfheal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TopologyEndToEndTest {

    private static final List<Process> runningProcesses = new ArrayList<>();
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void startTopology() throws Exception {
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        File jarFile = new File("target/service-app.jar");
        if (!jarFile.exists()) {
            jarFile = new File("service-app/target/service-app.jar");
        }
        String jarPath = jarFile.getAbsolutePath();
        assertTrue(jarFile.exists(), "service-app.jar must exist: " + jarPath);

        // 1. Start leaf services
        runningProcesses.add(startService(javaBin, jarPath, "text", 8083, null));
        runningProcesses.add(startService(javaBin, jarPath, "media", 8084, null));
        runningProcesses.add(startService(javaBin, jarPath, "user", 8085, null));
        runningProcesses.add(startService(javaBin, jarPath, "post-storage", 8086, null));

        // 2. Start compose-post
        runningProcesses.add(startService(javaBin, jarPath, "compose-post", 8082, "text:8083,media:8084,user:8085,post-storage:8086"));

        // 3. Start gateway
        runningProcesses.add(startService(javaBin, jarPath, "gateway", 8081, "compose-post:8082"));

        // Wait until gateway is UP and responding
        boolean gatewayUp = false;
        long deadline = System.currentTimeMillis() + 45000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:8081/work"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonNode root = mapper.readTree(resp.body());
                    if (!root.path("degraded").asBoolean(true)) {
                        gatewayUp = true;
                        break;
                    }
                }
            } catch (Exception ignored) {}
            Thread.sleep(1000);
        }

        assertTrue(gatewayUp, "Gateway (port 8081) failed to become healthy within 45s");
    }

    private static Process startService(String javaBin, String jarPath, String name, int port, String downstream) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                javaBin,
                "-Xmx128m",
                "-jar",
                jarPath,
                "--svc.name=" + name,
                "--server.port=" + port
        ));
        if (downstream != null && !downstream.isBlank()) {
            command.add("--svc.downstream=" + downstream);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        File logDir = new File("target/service-logs");
        logDir.mkdirs();
        File logFile = new File(logDir, name + ".log");
        pb.redirectOutput(logFile);
        pb.redirectError(logFile);
        return pb.start();
    }

    @AfterAll
    static void stopTopology() {
        for (Process p : runningProcesses) {
            try {
                p.destroyForcibly();
            } catch (Exception ignored) {}
        }
    }

    @Test
    void testEndToEndWorkTree() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:8081/work"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        JsonNode root = mapper.readTree(resp.body());
        assertEquals("gateway", root.get("service").asText());
        assertEquals(8081, root.get("port").asInt());
        assertEquals("OK", root.get("status").asText());
        assertFalse(root.get("degraded").asBoolean());

        // Verify compose-post downstream
        JsonNode compose = root.path("downstream").path("compose-post");
        assertFalse(compose.isMissingNode());
        assertEquals("compose-post", compose.get("service").asText());

        // Verify all 4 leaves are aggregated
        JsonNode leaves = compose.path("downstream");
        assertFalse(leaves.path("text").isMissingNode());
        assertFalse(leaves.path("media").isMissingNode());
        assertFalse(leaves.path("user").isMissingNode());
        assertFalse(leaves.path("post-storage").isMissingNode());
    }

    @Test
    void testTelemetryEndpoint() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:8081/internal/telemetry"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        JsonNode root = mapper.readTree(resp.body());
        assertEquals("gateway", root.get("serviceName").asText());
        assertEquals(8081, root.get("port").asInt());
        assertTrue(root.has("nodeMetrics"));
        assertTrue(root.has("edgeStats"));
    }
}
