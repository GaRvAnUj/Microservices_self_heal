package com.selfheal.healer.collector;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores mean and standard deviation per signal established under healthy traffic.
 * Computes statistical z-scores: z = (current - mean) / max(std, epsilon).
 */
@Component
public class BaselineProfileManager {

    public record SignalBaseline(double mean, double std, int sampleCount) {}

    private final Map<String, SignalBaseline> baselines = new ConcurrentHashMap<>();
    private volatile boolean baselineEstablished = false;
    private static final double EPSILON = 0.001; // Avoid division by zero

    public void trainBaseline(List<Map<String, Double>> samples) {
        if (samples == null || samples.isEmpty()) return;

        Map<String, List<Double>> valuesPerSignal = new HashMap<>();
        for (Map<String, Double> sample : samples) {
            for (Map.Entry<String, Double> entry : entrySet(sample)) {
                valuesPerSignal.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
            }
        }

        baselines.clear();
        for (Map.Entry<String, List<Double>> entry : valuesPerSignal.entrySet()) {
            String signal = entry.getKey();
            List<Double> values = entry.getValue();
            int n = values.size();
            if (n == 0) continue;

            double sum = 0.0;
            for (double v : values) sum += v;
            double mean = sum / n;

            double sumSqDiff = 0.0;
            for (double v : values) {
                sumSqDiff += Math.pow(v - mean, 2);
            }
            double std = Math.sqrt(sumSqDiff / n);
            if (std < EPSILON) {
                std = EPSILON;
            }

            baselines.put(signal, new SignalBaseline(mean, std, n));
        }

        baselineEstablished = !baselines.isEmpty();
    }

    public double computeZScore(String signalKey, double currentValue) {
        SignalBaseline base = baselines.get(signalKey);
        if (base == null) {
            return 0.0;
        }
        return (currentValue - base.mean()) / Math.max(base.std(), EPSILON);
    }

    public Map<String, Double> computeAllZScores(Map<String, Double> sample) {
        Map<String, Double> zScores = new LinkedHashMap<>();
        if (sample == null) return zScores;

        for (Map.Entry<String, Double> entry : sample.entrySet()) {
            zScores.put(entry.getKey(), computeZScore(entry.getKey(), entry.getValue()));
        }
        return zScores;
    }

    public boolean isBaselineEstablished() {
        return baselineEstablished;
    }

    public void setBaselineEstablished(boolean established) {
        this.baselineEstablished = established;
    }

    public Map<String, SignalBaseline> getAllBaselines() {
        return Collections.unmodifiableMap(baselines);
    }

    public void setSignalBaseline(String signalKey, double mean, double std) {
        baselines.put(signalKey, new SignalBaseline(mean, Math.max(std, EPSILON), 1));
        baselineEstablished = true;
    }

    private static Set<Map.Entry<String, Double>> entrySet(Map<String, Double> map) {
        return map != null ? map.entrySet() : Collections.emptySet();
    }
}
