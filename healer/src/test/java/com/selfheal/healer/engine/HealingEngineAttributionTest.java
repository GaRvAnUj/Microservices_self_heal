package com.selfheal.healer.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class HealingEngineAttributionTest {

    private final HealingEngine engine = new HealingEngine(
            null, null, null, null, null, null
    );

    @Test
    void testWrongTargetWhenCalleeIsAbnormalAfterLinkAction() {
        // Acted on link "compose-post->text", but callee "text" has high z-score (z = 5.0)
        String target = "compose-post->text";
        double targetZ = 4.2;
        Map<String, Double> zScores = Map.of(
                "link:compose-post->text:avg_latency", 4.2,
                "node:text:cpu_process", 5.0
        );

        String verdict = engine.determineAttributionVerdict(target, targetZ, zScores);
        assertEquals("WRONG_TARGET", verdict, "N3 attribution must conclude WRONG_TARGET when callee node is abnormal");
    }

    @Test
    void testIneffectiveActionWhenTargetRemainsAbnormal() {
        // Acted on node "media", post-action z is still 5.5, other components are normal (< 1.5 * 5.5)
        String target = "media";
        double targetZ = 5.5;
        Map<String, Double> zScores = Map.of(
                "node:media:cpu_process", 5.5,
                "node:gateway:cpu_process", 0.5,
                "node:compose-post:cpu_process", 0.2
        );

        String verdict = engine.determineAttributionVerdict(target, targetZ, zScores);
        assertEquals("INEFFECTIVE_ACTION", verdict, "N3 attribution must conclude INEFFECTIVE_ACTION when target is still abnormal");
    }

    @Test
    void testWrongTargetWhenAnotherComponentSpikesAndTargetIsClean() {
        // Acted on "compose-post", its z dropped to 1.0 (< 2.5), but "user" is at z = 4.5
        String target = "compose-post";
        double targetZ = 1.0;
        Map<String, Double> zScores = Map.of(
                "node:compose-post:cpu_process", 1.0,
                "node:user:cpu_process", 4.5
        );

        String verdict = engine.determineAttributionVerdict(target, targetZ, zScores);
        assertEquals("WRONG_TARGET", verdict, "N3 attribution must conclude WRONG_TARGET when target is cured but another node is at fault");
    }

    @Test
    void testInconclusiveWhenAmbiguous() {
        // Acted on "user", targetZ = 3.0 (between 2.5 and 4.0), no other node >= 4.0
        String target = "user";
        double targetZ = 3.0;
        Map<String, Double> zScores = Map.of(
                "node:user:cpu_process", 3.0,
                "node:gateway:cpu_process", 2.0
        );

        String verdict = engine.determineAttributionVerdict(target, targetZ, zScores);
        assertEquals("INCONCLUSIVE", verdict);
    }
}
