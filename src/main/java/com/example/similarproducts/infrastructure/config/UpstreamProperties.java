package com.example.similarproducts.infrastructure.config;

import java.net.URI;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("upstream")
public record UpstreamProperties(URI baseUrl, int maxConcurrency) {

    public UpstreamProperties {
        Objects.requireNonNull(baseUrl, "upstream.base-url is required");
        if (!("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || baseUrl.getHost() == null || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("upstream.base-url must be an absolute HTTP(S) base URL");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("upstream.max-concurrency must be positive");
        }
    }
}
