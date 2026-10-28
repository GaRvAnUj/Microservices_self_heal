package com.selfheal.healer.engine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Manages the Q-policy matrix in H2 and records labelled samples for retraining.
 * Q-update discipline:
 * - Success: q <- q + 0.3 * (1.0 - q)
 * - Ineffective Action: q <- q * 0.3
 */
@Service
public class PolicyService {
    private static final Logger log = Logger.getLogger(PolicyService.class.getName());

    public record QEntry(String faultType, String actionName, double qValue, int updateCount) {}

    private final JdbcTemplate jdbcTemplate;

    public PolicyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String getBestAction(String faultType) {
        String sql = "SELECT action_name FROM q_policy WHERE fault_type = ? ORDER BY q_value DESC LIMIT 1";
        List<String> results = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("action_name"), faultType);
        if (!results.isEmpty()) {
            return results.get(0);
        }
        // Fallback default based on fault type
        if ("cpu_hog".equals(faultType) || "crash".equals(faultType)) return "restart";
        if ("link_delay".equals(faultType)) return "tune_link";
        if ("link_loss".equals(faultType)) return "breaker";
        return "restart";
    }

    public Double getQValue(String faultType, String actionName) {
        List<Double> values = jdbcTemplate.query(
                "SELECT q_value FROM q_policy WHERE fault_type = ? AND action_name = ?",
                (rs, rowNum) -> rs.getDouble("q_value"),
                faultType,
                actionName
        );
        return values.isEmpty() ? null : values.get(0);
    }

    public synchronized void resetForExperiment(boolean wrongInitialLinkLossQ) {
        jdbcTemplate.update("UPDATE q_policy SET q_value = 0.9, update_count = 0 WHERE fault_type IN ('cpu_hog', 'crash')");
        jdbcTemplate.update("UPDATE q_policy SET q_value = 0.9, update_count = 0 WHERE fault_type = 'link_delay' AND action_name = 'tune_link'");
        jdbcTemplate.update("UPDATE q_policy SET q_value = 0.5, update_count = 0 WHERE fault_type = 'link_delay' AND action_name = 'breaker'");
        jdbcTemplate.update(
                "UPDATE q_policy SET q_value = ?, update_count = 0 WHERE fault_type = 'link_loss' AND action_name = 'breaker'",
                wrongInitialLinkLossQ ? 0.5 : 0.9
        );
        jdbcTemplate.update(
                "UPDATE q_policy SET q_value = ?, update_count = 0 WHERE fault_type = 'link_loss' AND action_name = 'tune_link'",
                wrongInitialLinkLossQ ? 0.9 : 0.5
        );
    }

    public String getAlternativeAction(String faultType, String currentAction) {
        String sql = "SELECT action_name FROM q_policy WHERE fault_type = ? AND action_name != ? ORDER BY q_value DESC LIMIT 1";
        List<String> results = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("action_name"), faultType, currentAction);
        if (!results.isEmpty()) {
            return results.get(0);
        }
        return "restart";
    }

    public synchronized void rewardAction(String faultType, String actionName) {
        String query = "SELECT q_value, update_count FROM q_policy WHERE fault_type = ? AND action_name = ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(query, faultType, actionName);
        if (!rows.isEmpty()) {
            double currentQ = ((Number) rows.get(0).get("q_value")).doubleValue();
            int count = ((Number) rows.get(0).get("update_count")).intValue();
            double newQ = currentQ + 0.3 * (1.0 - currentQ);

            jdbcTemplate.update(
                    "UPDATE q_policy SET q_value = ?, update_count = ? WHERE fault_type = ? AND action_name = ?",
                    newQ, count + 1, faultType, actionName
            );
            log.info(String.format("Q-Policy REWARDED [%s][%s]: %.3f -> %.3f", faultType, actionName, currentQ, newQ));
        }
    }

    public synchronized void penalizeAction(String faultType, String actionName) {
        String query = "SELECT q_value, update_count FROM q_policy WHERE fault_type = ? AND action_name = ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(query, faultType, actionName);
        if (!rows.isEmpty()) {
            double currentQ = ((Number) rows.get(0).get("q_value")).doubleValue();
            int count = ((Number) rows.get(0).get("update_count")).intValue();
            double newQ = currentQ * 0.3;

            jdbcTemplate.update(
                    "UPDATE q_policy SET q_value = ?, update_count = ? WHERE fault_type = ? AND action_name = ?",
                    newQ, count + 1, faultType, actionName
            );
            log.info(String.format("Q-Policy PENALIZED [%s][%s]: %.3f -> %.3f", faultType, actionName, currentQ, newQ));
        }
    }

    public List<QEntry> getAllQEntries() {
        return jdbcTemplate.query(
                "SELECT fault_type, action_name, q_value, update_count FROM q_policy ORDER BY fault_type, q_value DESC",
                (rs, rowNum) -> new QEntry(
                        rs.getString("fault_type"),
                        rs.getString("action_name"),
                        rs.getDouble("q_value"),
                        rs.getInt("update_count")
                )
        );
    }

    public void saveLabelledSample(String targetEntity, String faultType, String featuresJson) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO labelled_samples (id, timestamp, features_json, true_faulty_component, fault_type) VALUES (?, ?, ?, ?, ?)",
                id, now, featuresJson, targetEntity, faultType
        );
    }
}
