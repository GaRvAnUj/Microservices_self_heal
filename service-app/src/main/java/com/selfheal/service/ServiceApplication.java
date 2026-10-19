package com.selfheal.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Single configurable Spring Boot microservice application.
 * Started with distinct arguments per role across the topology.
 */
@SpringBootApplication(scanBasePackages = {"com.selfheal.service", "com.selfheal.common"})
public class ServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceApplication.class, args);
    }
}
