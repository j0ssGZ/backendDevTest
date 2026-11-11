package com.example.similarproducts.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("resilience")
public record ResilienceProperties(
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("500ms") Duration acquireTimeout,
        @DefaultValue("6s") Duration responseTimeout,
        @DefaultValue("7s") Duration callTimeout,
        @DefaultValue("10s") Duration requestTimeout,
        @DefaultValue("32") int maxConnections,
        @DefaultValue("64") int maxPendingAcquisitions,
        @DefaultValue("30s") Duration maxIdleTime,
        @DefaultValue("15s") Duration evictionInterval) {

    public ResilienceProperties {
        for (Duration value : new Duration[] {connectTimeout, acquireTimeout, responseTimeout,
                callTimeout, requestTimeout, maxIdleTime, evictionInterval}) {
            if (value == null || value.isNegative() || value.toMillis() < 1) {
                throw new IllegalArgumentException("Resilience durations must be at least one millisecond");
            }
        }
        if (connectTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Connect timeout exceeds Netty's millisecond range");
        }
        if (maxConnections < 1 || maxPendingAcquisitions < 1) {
            throw new IllegalArgumentException("Pool connection and pending limits must be positive and finite");
        }
    }
}
