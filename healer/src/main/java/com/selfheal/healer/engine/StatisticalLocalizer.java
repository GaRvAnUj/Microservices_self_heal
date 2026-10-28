package com.selfheal.healer.engine;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Statistical fault localizer implementing N1: Link-Level Diagnosis & Disambiguation.
 *
 * DISAMBIGUATION LOGIC:
 * If link (A -> B) is abnormal (high latency or error rate with z >= 4), inspect callee B's
 * internal node metrics (CPU, memory, log error events):
 * - If B's internal signals are normal (z < 3.0): classify as LINK FAULT on (A -> B).
 * - If B's internal signals are also abnormal (z >= 3.0): blame NODE B.
 *
 * NOTE: Marked as JAVA_STATISTICAL_APPROXIMATION.
 */
@Component
public class StatisticalLocalizer {
    public static final double LINK_CANDIDATE_Z_THRESHOLD = 4.0;
    public static final double CALLEE_ABNORMALITY_Z_THRESHOLD = 3.0;

    public DiagnosisClient.DiagnosisResult localize(Map<String, Double> zScores, Map<String, Double> smoothedSignals) {
        return localize(zScores, smoothedSignals, true);
    }

    public DiagnosisClient.DiagnosisResult localize(Map<String, Double> zScores,
                                                   Map<String, Double> smoothedSignals,
                                                   boolean allowLinkCandidates) {
        return localize(zScores, smoothedSignals, allowLinkCandidates, Set.of());
    }

    public DiagnosisClient.DiagnosisResult localize(Map<String, Double> zScores,
                                                   Map<String, Double> smoothedSignals,
                                                   boolean allowLinkCandidates,
                                                   Set<String> excludedTargets) {
        if (zScores == null || zScores.isEmpty()) {
            return new DiagnosisClient.DiagnosisResult(false, null, null, false, 0.0, "JAVA_STATISTICAL_APPROXIMATION");
        }
        Set<String> excluded = excludedTargets == null ? Set.of() : excludedTargets;

        if (!allowLinkCandidates) {
            Map<String, Double> nodeScores = new LinkedHashMap<>();
            for (Map.Entry<String, Double> entry : zScores.entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("link:")) {
                    nodeScores.put(key, entry.getValue());
                }
            }
            zScores = nodeScores;
        }

        // 1. Group z-scores by links and nodes
        Map<String, Double> linkLatencyZ = new HashMap<>();
        Map<String, Double> linkErrorZ = new HashMap<>();
        Map<String, Double> nodeCpuZ = new HashMap<>();
        Map<String, Double> nodeLogZ = new HashMap<>();

        for (Map.Entry<String, Double> entry : zScores.entrySet()) {
            String key = entry.getKey();
            double z = entry.getValue();

            if (key.startsWith("link:") && key.contains("->")) {
                // e.g. "link:compose-post->text:avg_latency"
                int colonIdx = key.indexOf(':', 5);
                if (colonIdx > 0) {
                    String link = key.substring(5, colonIdx);
                    String metric = key.substring(colonIdx + 1);
                    if ("avg_latency".equals(metric)) {
                        linkLatencyZ.put(link, z);
                    } else if ("error_rate".equals(metric)) {
                        linkErrorZ.put(link, z);
                    }
                }
            } else if (key.startsWith("node:")) {
                // e.g. "node:text:cpu_process"
                String[] parts = key.split(":");
                if (parts.length >= 3) {
                    String node = parts[1];
                    String metric = parts[2];
                    if (metric.startsWith("cpu")) {
                        nodeCpuZ.merge(node, z, Math::max);
                    }
                }
            } else if (key.startsWith("log:")) {
                // e.g. "log:text:DOWNSTREAM_ERROR"
                String[] parts = key.split(":");
                if (parts.length >= 3) {
                    String node = parts[1];
                    nodeLogZ.merge(node, z, Math::max);
                }
            }
        }

        // 2. Identify candidate abnormal links (z >= 4.0)
        String worstLink = null;
        double maxLinkZ = LINK_CANDIDATE_Z_THRESHOLD - 0.01;
        boolean isDelay = true;
        boolean worstCandidateIsNode = false;

        Set<String> allLinks = new HashSet<>();
        allLinks.addAll(linkLatencyZ.keySet());
        allLinks.addAll(linkErrorZ.keySet());

        for (String link : allLinks) {
            double latZ = linkLatencyZ.getOrDefault(link, 0.0);
            double errZ = linkErrorZ.getOrDefault(link, 0.0);
            double higherZ = Math.max(latZ, errZ);

            String[] parts = link.split("->");
            String callee = parts.length > 1 ? parts[1].trim() : "";
            double calleeAbnormality = Math.max(
                    nodeCpuZ.getOrDefault(callee, 0.0),
                    nodeLogZ.getOrDefault(callee, 0.0)
            );
            boolean candidateIsNode = calleeAbnormality >= CALLEE_ABNORMALITY_Z_THRESHOLD;
            String candidate = candidateIsNode ? callee : link;
            if (!excluded.contains(candidate) && higherZ > maxLinkZ) {
                maxLinkZ = higherZ;
                worstLink = link;
                isDelay = (latZ >= errZ);
                worstCandidateIsNode = candidateIsNode;
            }
        }

        // 3. Apply N1 Disambiguation if an abnormal link is detected
        if (worstLink != null) {
            String[] parts = worstLink.split("->");
            String callee = parts.length > 1 ? parts[1].trim() : "";

            double calleeCpuZ = nodeCpuZ.getOrDefault(callee, 0.0);
            double calleeLogZ = nodeLogZ.getOrDefault(callee, 0.0);
            double calleeAbnormality = Math.max(calleeCpuZ, calleeLogZ);

            if (!worstCandidateIsNode) {
                // Callee is healthy -> Pure LINK FAULT on (caller -> callee)
                String faultType = isDelay ? "link_delay" : "link_loss";
                return new DiagnosisClient.DiagnosisResult(
                        true,
                        worstLink,
                        faultType,
                        true,
                        Math.min(1.0, maxLinkZ / 10.0),
                        "JAVA_STATISTICAL_APPROXIMATION"
                );
            } else {
                // Callee itself is degraded -> Blame callee node
                String faultType = (calleeCpuZ > calleeLogZ) ? "cpu_hog" : "crash";
                return new DiagnosisClient.DiagnosisResult(
                        true,
                        callee,
                        faultType,
                        false,
                        Math.min(1.0, calleeAbnormality / 10.0),
                        "JAVA_STATISTICAL_APPROXIMATION"
                );
            }
        }

        // 4. No abnormal link found; look for abnormal nodes (z >= 4.0)
        String worstNode = null;
        double maxNodeZ = LINK_CANDIDATE_Z_THRESHOLD - 0.01;
        String detectedFault = "crash";

        Set<String> allNodes = new HashSet<>();
        allNodes.addAll(nodeCpuZ.keySet());
        allNodes.addAll(nodeLogZ.keySet());

        for (String node : allNodes) {
            if (excluded.contains(node)) {
                continue;
            }
            double cpuZ = nodeCpuZ.getOrDefault(node, 0.0);
            double logZ = nodeLogZ.getOrDefault(node, 0.0);
            double higherZ = Math.max(cpuZ, logZ);

            if (higherZ > maxNodeZ) {
                maxNodeZ = higherZ;
                worstNode = node;
                detectedFault = (cpuZ >= logZ) ? "cpu_hog" : "crash";
            }
        }

        if (worstNode != null) {
            return new DiagnosisClient.DiagnosisResult(
                    true,
                    worstNode,
                    detectedFault,
                    false,
                    Math.min(1.0, maxNodeZ / 10.0),
                    "JAVA_STATISTICAL_APPROXIMATION"
            );
        }

        // 5. No abnormal z-scores detected
        return new DiagnosisClient.DiagnosisResult(false, null, null, false, 0.0, "JAVA_STATISTICAL_APPROXIMATION");
    }

    public String inferLinkFaultType(Map<String, Double> zScores) {
        Map<String, double[]> linkScores = new HashMap<>();
        if (zScores != null) {
            for (Map.Entry<String, Double> entry : zScores.entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("link:") || !key.contains("->")) {
                    continue;
                }
                int separator = key.indexOf(':', 5);
                if (separator <= 0) {
                    continue;
                }
                double[] metrics = linkScores.computeIfAbsent(key.substring(5, separator), ignored -> new double[2]);
                String metric = key.substring(separator + 1);
                if ("avg_latency".equals(metric)) {
                    metrics[0] = Math.max(metrics[0], entry.getValue());
                } else if ("error_rate".equals(metric)) {
                    metrics[1] = Math.max(metrics[1], entry.getValue());
                }
            }
        }

        double maxLatency = 0.0;
        double maxErrors = 0.0;
        for (double[] metrics : linkScores.values()) {
            maxLatency = Math.max(maxLatency, metrics[0]);
            maxErrors = Math.max(maxErrors, metrics[1]);
        }
        if (Math.max(maxLatency, maxErrors) < LINK_CANDIDATE_Z_THRESHOLD) {
            return null;
        }
        return maxLatency >= maxErrors ? "link_delay" : "link_loss";
    }
}
