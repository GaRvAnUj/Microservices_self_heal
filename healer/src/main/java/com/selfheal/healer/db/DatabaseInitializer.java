package com.selfheal.healer.db;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.logging.Logger;

/**
 * Initializes embedded H2 database tables: incidents, incident_actions, q_policy, labelled_samples.
 */
@Component
public class DatabaseInitializer {
    private static final Logger log = Logger.getLogger(DatabaseInitializer.class.getName());

    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        File dataDir = new File("./data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        log.info("Initializing H2 database tables...");

        // 1. Incidents table
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS incidents (
                id VARCHAR(36) PRIMARY KEY,
                started_at BIGINT NOT NULL,
                resolved_at BIGINT,
                trigger_reason VARCHAR(255),
                mode VARCHAR(50),
                root_cause_type VARCHAR(50),
                target_entity VARCHAR(100),
                final_status VARCHAR(50)
            )
        """);

        // 2. Incident actions table
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS incident_actions (
                id VARCHAR(36) PRIMARY KEY,
                incident_id VARCHAR(36) NOT NULL,
                step_num INT NOT NULL,
                action_type VARCHAR(50) NOT NULL,
                target_component VARCHAR(100) NOT NULL,
                started_at BIGINT NOT NULL,
                completed_at BIGINT,
                post_action_z DOUBLE,
                verdict VARCHAR(50)
            )
        """);

        // 3. Q-Policy table
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS q_policy (
                fault_type VARCHAR(50) NOT NULL,
                action_name VARCHAR(50) NOT NULL,
                q_value DOUBLE NOT NULL,
                update_count INT NOT NULL DEFAULT 0,
                PRIMARY KEY (fault_type, action_name)
            )
        """);

        // 4. Labelled samples for offline model retraining
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS labelled_samples (
                id VARCHAR(36) PRIMARY KEY,
                timestamp BIGINT NOT NULL,
                features_json CLOB NOT NULL,
                true_faulty_component VARCHAR(100) NOT NULL,
                fault_type VARCHAR(50) NOT NULL
            )
        """);

        // Prepopulate default Q-values if table is empty
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM q_policy", Integer.class);
        if (count != null && count == 0) {
            log.info("Pre-populating initial Q-policy table...");
            insertQPolicy("cpu_hog", "restart", 0.9);
            insertQPolicy("crash", "restart", 0.9);
            insertQPolicy("link_delay", "tune_link", 0.9);
            insertQPolicy("link_delay", "breaker", 0.5);
            insertQPolicy("link_loss", "breaker", 0.9);
            insertQPolicy("link_loss", "tune_link", 0.5);
        }

        log.info("H2 database initialization completed.");
    }

    private void insertQPolicy(String faultType, String actionName, double qValue) {
        jdbcTemplate.update(
                "INSERT INTO q_policy (fault_type, action_name, q_value, update_count) VALUES (?, ?, ?, 0)",
                faultType, actionName, qValue
        );
    }
}
