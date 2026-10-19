package com.selfheal.healer.db;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class DatabaseInitializerTest {

    @Test
    void testDatabaseInitializationAndSchemaCreation() {
        String dbName = "testdb_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer initializer = new DatabaseInitializer(jdbcTemplate);
        initializer.initialize();

        // Verify tables exist
        Integer incidentCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM incidents", Integer.class);
        assertNotNull(incidentCount);
        assertEquals(0, incidentCount);

        Integer actionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM incident_actions", Integer.class);
        assertNotNull(actionCount);
        assertEquals(0, actionCount);

        Integer sampleCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM labelled_samples", Integer.class);
        assertNotNull(sampleCount);
        assertEquals(0, sampleCount);

        // Verify pre-populated Q-policy rules
        Integer qCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM q_policy", Integer.class);
        assertNotNull(qCount);
        assertEquals(6, qCount);

        Double cpuRestartQ = jdbcTemplate.queryForObject(
                "SELECT q_value FROM q_policy WHERE fault_type = 'cpu_hog' AND action_name = 'restart'",
                Double.class
        );
        assertNotNull(cpuRestartQ);
        assertEquals(0.9, cpuRestartQ, 0.001);

        Double linkDelayTuneQ = jdbcTemplate.queryForObject(
                "SELECT q_value FROM q_policy WHERE fault_type = 'link_delay' AND action_name = 'tune_link'",
                Double.class
        );
        assertNotNull(linkDelayTuneQ);
        assertEquals(0.9, linkDelayTuneQ, 0.001);

        Double linkLossBreakerQ = jdbcTemplate.queryForObject(
                "SELECT q_value FROM q_policy WHERE fault_type = 'link_loss' AND action_name = 'breaker'",
                Double.class
        );
        assertNotNull(linkLossBreakerQ);
        assertEquals(0.9, linkLossBreakerQ, 0.001);
    }
}
