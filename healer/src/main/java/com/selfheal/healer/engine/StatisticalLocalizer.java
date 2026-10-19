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

    public DiagnosisClient.DiagnosisResult localize(Map<String, Double> zScores, Map<String, Double> smoothedSignals) {
        if (zScores == null || zScores.isEmpty()) {
            return new DiagnosisClient.DiagnosisResult(false, null, null, false, 0.0, "JAVA_STATISTICAL_APPROXIMATION");
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
        double maxLinkZ = 3.99; // threshold: z >= 4.0
        boolean isDelay = true;

        Set<String> allLinks = new HashSet<>();
        allLinks.addAll(linkLatencyZ.keySet());
        allLinks.addAll(linkErrorZ.keySet());

        for (String link : allLinks) {
            double latZ = linkLatencyZ.getOrDefault(link, 0.0);
            double errZ = linkErrorZ.getOrDefault(link, 0.0);
            double higherZ = Math.max(latZ, errZ);

            if (higherZ > maxLinkZ) {
                maxLinkZ = higherZ;
                worstLink = link;
                isDelay = (latZ >= errZ);
            }
        }

        // 3. Apply N1 Disambiguation if an abnormal link is detected
        if (worstLink != null) {
            String[] parts = worstLink.split("->");
            String caller = parts[0].trim();
            String callee = parts.length > 1 ? parts[1].trim() : "";

            double calleeCpuZ = nodeCpuZ.getOrDefault(callee, 0.0);
            double calleeLogZ = nodeLogZ.getOrDefault(callee, 0.0);
            double calleeAbnormality = Math.max(calleeCpuZ, calleeLogZ);

            if (calleeAbnormality < 3.0) {
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
        double maxNodeZ = 3.99;
        String detectedFault = "crash";

        Set<String> allNodes = new HashSet<>();
        allNodes.addAll(nodeCpuZ.keySet());
        allNodes.addAll(nodeLogZ.keySet());

        for (String node : allNodes) {
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
}
