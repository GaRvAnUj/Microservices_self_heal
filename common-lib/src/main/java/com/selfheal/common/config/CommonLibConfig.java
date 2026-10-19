package com.selfheal.common.config;

import com.selfheal.common.chaos.ChaosRegistryCache;
import com.selfheal.common.client.LinkClient;
import com.selfheal.common.controller.CommonChaosController;
import com.selfheal.common.policy.EdgePolicyRegistry;
import com.selfheal.common.telemetry.LogEventTracker;
import com.selfheal.common.telemetry.NodeMetricsCollector;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Autoconfiguration wiring all common resilience, telemetry, and chaos beans.
 */
@Configuration
public class CommonLibConfig {

    @Value("${svc.name:unknown-service}")
    private String serviceName;

    @Value("${chaos.registry.url:http://localhost:8080/chaos/registry}")
    private String chaosRegistryUrl;

    @Bean
    @ConditionalOnMissingBean
    public EdgePolicyRegistry edgePolicyRegistry() {
        return new EdgePolicyRegistry();
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    public ChaosRegistryCache chaosRegistryCache() {
        ChaosRegistryCache cache = new ChaosRegistryCache(chaosRegistryUrl);
        cache.start();
        return cache;
    }

    @Bean
    @ConditionalOnMissingBean
    public LogEventTracker logEventTracker() {
        return new LogEventTracker();
    }

    @Bean
    @ConditionalOnMissingBean
    public NodeMetricsCollector nodeMetricsCollector() {
        return new NodeMetricsCollector();
    }

    @Bean
    @ConditionalOnMissingBean
    public LinkClient linkClient(EdgePolicyRegistry policyRegistry,
                                 ChaosRegistryCache chaosCache,
                                 LogEventTracker logTracker) {
        return new LinkClient(serviceName, policyRegistry, chaosCache, logTracker);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommonChaosController commonChaosController(EdgePolicyRegistry policyRegistry,
                                                       LinkClient linkClient,
                                                       NodeMetricsCollector metricsCollector,
                                                       LogEventTracker logTracker) {
        return new CommonChaosController(policyRegistry, linkClient, metricsCollector, logTracker);
    }
}
