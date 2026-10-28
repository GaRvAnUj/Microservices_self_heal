package com.selfheal.healer.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the lifecycle of microservice OS processes via ProcessBuilder.
 * Constrains each JVM to -Xmx128m and tracks uptime/downtime.
 */
@Component
public class ServiceManager {
    private static final Logger log = Logger.getLogger(ServiceManager.class.getName());
    private static final long RESTART_HEALTH_TIMEOUT_MS = 45_000;

    public record ServiceDef(String name, int port, String downstream, boolean critical) {}

    public static class ServiceStatus {
        private final String name;
        private final int port;
        private Long pid;
        private String state; // UP, DOWN, RESTARTING
        private long lastStartTime;
        private long totalDowntimeMs;
        private int restartCount;

        public ServiceStatus(String name, int port) {
            this.name = name;
            this.port = port;
            this.state = "DOWN";
            this.restartCount = 0;
            this.totalDowntimeMs = 0;
        }

        public synchronized String getName() { return name; }
        public synchronized int getPort() { return port; }
        public synchronized Long getPid() { return pid; }
        public synchronized String getState() { return state; }
        public synchronized int getRestartCount() { return restartCount; }
        public synchronized long getTotalDowntimeMs() { return totalDowntimeMs; }

        public synchronized void markStarted(long pid) {
            this.pid = pid;
            this.state = "UP";
            this.lastStartTime = System.currentTimeMillis();
        }

        public synchronized void markDown() {
            if ("UP".equals(state) && lastStartTime > 0) {
                // process transition to down
            }
            this.state = "DOWN";
            this.pid = null;
        }

        public synchronized void markRestarting() {
            this.state = "RESTARTING";
            this.restartCount++;
        }

        public synchronized void addDowntime(long ms) {
            this.totalDowntimeMs += ms;
        }
    }

    private final List<ServiceDef> serviceDefs = List.of(
            new ServiceDef("text", 8083, null, false),
            new ServiceDef("media", 8084, null, false),
            new ServiceDef("user", 8085, null, false),
            new ServiceDef("post-storage", 8086, null, true),
            new ServiceDef("compose-post", 8082, "text:8083,media:8084,user:8085,post-storage:8086", false),
            new ServiceDef("gateway", 8081, "compose-post:8082", false)
    );

    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, ServiceStatus> statuses = new ConcurrentHashMap<>();
    private final HttpClient httpClient;

    public ServiceManager() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(800))
                .build();
        for (ServiceDef def : serviceDefs) {
            statuses.put(def.name(), new ServiceStatus(def.name(), def.port()));
        }
    }

    @PostConstruct
    public synchronized void startAll() {
        File jarFile = findServiceJar();
        if (jarFile == null || !jarFile.exists()) {
            log.warning("Could not locate service-app.jar! Ensure 'mvn package' has run.");
            return;
        }

        File logsDir = new File("logs");
        if (!logsDir.exists()) logsDir.mkdirs();

        File projectDir = resolveProjectRoot(jarFile);
        startDiagnosisService(projectDir, logsDir);

        for (ServiceDef def : serviceDefs) {
            if (!isHealthy(def.name())) {
                startServiceProcess(def, jarFile, logsDir);
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    public synchronized boolean restartService(String serviceName) {
        ServiceDef def = getServiceDef(serviceName);
        if (def == null) {
            log.warning("Unknown service name for restart: " + serviceName);
            return false;
        }

        long restartStart = System.currentTimeMillis();
        ServiceStatus status = statuses.get(serviceName);
        status.markRestarting();

        // 1. Terminate existing process if alive
        Process existing = processes.remove(serviceName);
        if (existing != null) {
            existing.destroyForcibly();
            try {
                existing.waitFor();
            } catch (InterruptedException ignored) {}
        }

        // 2. Start new instance
        File jarFile = findServiceJar();
        File logsDir = new File("logs");
        startServiceProcess(def, jarFile, logsDir);

        // 3. Poll health while the Spring Boot service starts
        boolean healthy = false;
        long deadline = System.currentTimeMillis() + RESTART_HEALTH_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (isHealthy(serviceName)) {
                healthy = true;
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}
        }

        long downtime = System.currentTimeMillis() - restartStart;
        status.addDowntime(downtime);

        if (healthy) {
            log.info("Service " + serviceName + " successfully restarted in " + downtime + " ms");
            return true;
        } else {
            log.severe("Service " + serviceName + " failed to recover after restart within "
                    + RESTART_HEALTH_TIMEOUT_MS / 1000 + "s");
            return false;
        }
    }

    public synchronized Map<String, Boolean> restartAll() {
        Map<String, Boolean> outcomes = new LinkedHashMap<>();
        for (ServiceDef def : serviceDefs) {
            outcomes.put(def.name(), restartService(def.name()));
        }
        return Collections.unmodifiableMap(outcomes);
    }

    public String getNeighborComponent(String component) {
        if (component == null || component.isBlank()) {
            return "gateway";
        }

        if (component.contains("->")) {
            String[] edge = component.split("->", 2);
            String caller = edge[0].trim();
            String target = edge.length > 1 ? edge[1].trim() : "";
            ServiceDef callerDef = getServiceDef(caller);
            if (callerDef != null && callerDef.downstream() != null) {
                for (String downstream : callerDef.downstream().split(",")) {
                    String neighbor = downstream.split(":")[0].trim();
                    if (!neighbor.equalsIgnoreCase(target)) {
                        return caller + "->" + neighbor;
                    }
                }
            }
            return caller;
        }

        for (ServiceDef def : serviceDefs) {
            if (def.downstream() == null) {
                continue;
            }
            for (String downstream : def.downstream().split(",")) {
                if (downstream.split(":")[0].trim().equalsIgnoreCase(component)) {
                    return def.name();
                }
            }
        }

        ServiceDef targetDef = getServiceDef(component);
        if (targetDef != null && targetDef.downstream() != null) {
            return targetDef.downstream().split(",")[0].split(":")[0].trim();
        }
        return "gateway";
    }

    private void startServiceProcess(ServiceDef def, File jarFile, File logsDir) {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        List<String> command = new ArrayList<>(List.of(
                javaBin,
                "-Xmx128m",
                "-jar",
                jarFile.getAbsolutePath(),
                "--svc.name=" + def.name(),
                "--server.port=" + def.port()
        ));
        if (def.downstream() != null && !def.downstream().isBlank()) {
            command.add("--svc.downstream=" + def.downstream());
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(resolveProjectRoot(jarFile)); // Root workspace dir
        String javaToolOptions = pb.environment().get("JAVA_TOOL_OPTIONS");
        if (javaToolOptions != null) {
            String childOptions = javaToolOptions
                    .replaceAll("(?<!\\S)-agentlib:jdwp(?:=\\S+)?", "")
                    .trim();
            if (childOptions.isEmpty()) {
                pb.environment().remove("JAVA_TOOL_OPTIONS");
            } else {
                pb.environment().put("JAVA_TOOL_OPTIONS", childOptions);
            }
        }
        File logFile = new File(logsDir, def.name() + ".log");
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile));

        try {
            Process p = pb.start();
            processes.put(def.name(), p);
            ServiceStatus status = statuses.get(def.name());
            status.markStarted(p.pid());
            log.info("Started process " + def.name() + " (PID " + p.pid() + ") on port " + def.port());
        } catch (IOException e) {
            log.log(Level.SEVERE, "Failed to start process " + def.name(), e);
        }
    }

    public boolean isHealthy(String serviceName) {
        ServiceDef def = getServiceDef(serviceName);
        if (def == null) return false;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + def.port() + "/internal/telemetry"))
                    .timeout(Duration.ofMillis(600))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @PreDestroy
    public synchronized void stopAll() {
        log.info("Shutting down all managed microservice processes...");
        for (Map.Entry<String, Process> entry : processes.entrySet()) {
            try {
                entry.getValue().destroyForcibly();
            } catch (Exception ignored) {}
            ServiceStatus status = statuses.get(entry.getKey());
            if (status != null) {
                status.markDown();
            }
        }
        processes.clear();
    }

    public ServiceDef getServiceDef(String name) {
        for (ServiceDef def : serviceDefs) {
            if (def.name().equalsIgnoreCase(name)) return def;
        }
        return null;
    }

    public List<ServiceDef> getServiceDefs() {
        return serviceDefs;
    }

    public Map<String, ServiceStatus> getStatuses() {
        for (ServiceDef def : serviceDefs) {
            ServiceStatus st = statuses.get(def.name());
            if (st != null && !"RESTARTING".equals(st.getState())) {
                if (isHealthy(def.name())) {
                    if (!"UP".equals(st.getState())) {
                        st.markStarted(def.port());
                    }
                } else {
                    if ("UP".equals(st.getState())) {
                        st.markDown();
                    }
                }
            }
        }
        return Collections.unmodifiableMap(statuses);
    }

    private File resolveProjectRoot(File jarFile) {
        File dir = jarFile.getAbsoluteFile().getParentFile(); // target
        if (dir != null && "target".equalsIgnoreCase(dir.getName())) {
            dir = dir.getParentFile(); // service-app or healer
        }
        if (dir != null && ("service-app".equalsIgnoreCase(dir.getName()) || "healer".equalsIgnoreCase(dir.getName()))) {
            dir = dir.getParentFile(); // root workspace
        }
        return dir != null ? dir : new File(".").getAbsoluteFile();
    }

    private void startDiagnosisService(File projectDir, File logsDir) {
        if (isDiagnosisHealthy()) return;
        File diagDir = new File(projectDir, "diagnosis-service");
        if (!diagDir.exists()) return;

        File modifyPython = new File(
                new File(new File(projectDir, ".venv-modify-training"), "Scripts"),
                "python.exe"
        );
        String pythonCommand = modifyPython.isFile() ? modifyPython.getAbsolutePath() : "python";
        List<String> command = List.of(
                pythonCommand,
                "-m",
                "uvicorn",
                "main:app",
                "--app-dir",
                diagDir.getAbsolutePath(),
                "--host",
                "127.0.0.1",
                "--port",
                "5000"
        );
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectDir);
        File logFile = new File(logsDir, "diagnosis-service.log");
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile));
        try {
            Process p = pb.start();
            processes.put("diagnosis-service", p);
            log.info("Started Python diagnosis-service (PID " + p.pid() + ") on port 5000");
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to auto-start Python diagnosis-service (fallback will be used)", e);
        }
    }

    public boolean isDiagnosisHealthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:5000/health"))
                    .timeout(Duration.ofMillis(600))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private File findServiceJar() {
        File f1 = new File("service-app/target/service-app.jar");
        if (f1.exists()) return f1;
        File f2 = new File("../service-app/target/service-app.jar");
        if (f2.exists()) return f2;
        File f3 = new File("target/service-app.jar");
        if (f3.exists()) return f3;
        return null;
    }
}
