package com.selfheal.healer.engine;

import com.selfheal.healer.db.DatabaseInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class PolicyServiceTest {

    private PolicyService policyService;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        String dbName = "testdb_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer dbInit = new DatabaseInitializer(jdbcTemplate);
        dbInit.initialize();

        policyService = new PolicyService(jdbcTemplate);
    }

    @Test
    void testBestActionSelection() {
        assertEquals("restart", policyService.getBestAction("cpu_hog"));
        assertEquals("tune_link", policyService.getBestAction("link_delay"));
        assertEquals("breaker", policyService.getBestAction("link_loss"));
    }

    @Test
    void testAlternativeActionSelection() {
        // For link_delay, alternative to tune_link is breaker
        assertEquals("breaker", policyService.getAlternativeAction("link_delay", "tune_link"));
        // For link_loss, alternative to breaker is tune_link
        assertEquals("tune_link", policyService.getAlternativeAction("link_loss", "breaker"));
    }

    @Test
    void testRewardAndPenalizeUpdates() {
        // Initial q for (cpu_hog, restart) is 0.9
        // Reward: q <- 0.9 + 0.3 * (1.0 - 0.9) = 0.9 + 0.03 = 0.93
        policyService.rewardAction("cpu_hog", "restart");
        Double q1 = jdbcTemplate.queryForObject(
                "SELECT q_value FROM q_policy WHERE fault_type = 'cpu_hog' AND action_name = 'restart'",
                Double.class
        );
        assertNotNull(q1);
        assertEquals(0.93, q1, 0.001);

        // Penalize: q <- 0.93 * 0.3 = 0.279
        policyService.penalizeAction("cpu_hog", "restart");
        Double q2 = jdbcTemplate.queryForObject(
                "SELECT q_value FROM q_policy WHERE fault_type = 'cpu_hog' AND action_name = 'restart'",
                Double.class
        );
        assertNotNull(q2);
        assertEquals(0.279, q2, 0.001);
    }
}
