package com.example.similarproducts.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("product-cache")
public record ProductCacheProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("1000") long maxEntries, @DefaultValue("30s") Duration ttl) {

    public ProductCacheProperties {
        if (maxEntries < 1 || ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Cache capacity and TTL must be positive and finite");
        }
    }
}
