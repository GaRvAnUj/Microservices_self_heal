package com.selfheal.healer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Healer control plane, load generator, anomaly detector, H2 persistence, and dashboard server.
 */
@SpringBootApplication
@EnableScheduling
public class HealerApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealerApplication.class, args);
    }
}
