package com.example.similarproducts.infrastructure.config;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.example.similarproducts.application.GetSimilarProducts;
import com.example.similarproducts.infrastructure.http.UpstreamException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import io.netty.channel.ChannelOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ResilienceConfigurationTests {

    @ParameterizedTest
    @MethodSource("invalidSettings")
    void invalidLimitsFailBinding(String setting) {
        runner(WebClient.builder()).withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    static Stream<Arguments> invalidSettings() {
        return Stream.of("connect-timeout=0ms", "acquire-timeout=-1s", "response-timeout=0s",
                        "call-timeout=0s", "request-timeout=0s", "max-connections=0", "max-pending-acquisitions=-1",
                        "max-idle-time=0s", "eviction-interval=0s")
                .map(value -> Arguments.of("resilience." + value));
    }

    @ParameterizedTest
    @MethodSource("invalidCacheSettings")
    void invalidCacheSettingsFailBinding(String setting) {
        runner(WebClient.builder()).withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    static Stream<Arguments> invalidCacheSettings() {
        return Stream.of(Arguments.of("product-cache.max-entries=0"), Arguments.of("product-cache.ttl=0s"));
    }

    @Test
    void actualWiringCachesOnlyDetailsAndDisposesItsDedicatedPoolOnShutdown() {
        var idCalls = new AtomicInteger();
        var detailCalls = new AtomicInteger();
        var provider = new java.util.concurrent.atomic.AtomicReference<ConnectionProvider>();
        runner(WebClient.builder().exchangeFunction(request -> {
            if (request.url().getPath().endsWith("/similarids")) {
                idCalls.incrementAndGet(); return Mono.just(json("[2]"));
            }
            detailCalls.incrementAndGet();
            return Mono.just(json("{\"id\":2,\"name\":\"Name\",\"price\":1.23400,\"availability\":false}"));
        })).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ResilienceProperties.class).maxConnections()).isEqualTo(32);
            assertThat(context.getBean(ProductCacheProperties.class).maxEntries()).isEqualTo(1000);
            var http = context.getBean(HttpClient.class).configuration();
            assertThat(http.options().get(ChannelOption.CONNECT_TIMEOUT_MILLIS)).isEqualTo(1000);
            assertThat(http.responseTimeout()).isEqualTo(Duration.ofSeconds(6));
            assertThat(http.isRetryDisabled()).isTrue();
            provider.set(context.getBean(ConnectionProvider.class));
            var useCase = context.getBean(GetSimilarProducts.class);
            for (int i = 0; i < 2; i++) {
                StepVerifier.create(useCase.getSimilarProducts("source"))
                        .assertNext(products -> assertThat(products.getFirst().price().toPlainString()).isEqualTo("1.23400"))
                        .verifyComplete();
            }
            assertThat(idCalls.get()).isEqualTo(2);
            assertThat(detailCalls.get()).isEqualTo(1);
            assertThat(context.getBean(Cache.class).estimatedSize()).isEqualTo(1);
        });
        assertThat(provider.get().isDisposed()).isTrue();
    }

    @Test
    void cacheCanBeDisabledForAnExplicitColdBaselineWithoutChangingTransport() {
        var detailCalls = new AtomicInteger();
        runner(WebClient.builder().exchangeFunction(request -> {
            if (request.url().getPath().endsWith("/similarids")) { return Mono.just(json("[2]")); }
            detailCalls.incrementAndGet();
            return Mono.just(json("{\"id\":2,\"name\":\"Name\",\"price\":1,\"availability\":true}"));
        })).withPropertyValues("product-cache.enabled=false").run(context -> {
            for (int i = 0; i < 2; i++) {
                StepVerifier.create(context.getBean(GetSimilarProducts.class).getSimilarProducts("source"))
                        .expectNextCount(1).verifyComplete();
            }
            assertThat(detailCalls.get()).isEqualTo(2);
        });
    }

    @Test
    void environmentOverridesBindWithoutChangingProductionFiles() {
        runner(WebClient.builder()).withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", Map.of(
                        "RESILIENCE_REQUESTTIMEOUT", "3s", "RESILIENCE_MAXCONNECTIONS", "2",
                        "PRODUCTCACHE_TTL", "5s", "PRODUCTCACHE_MAXENTRIES", "7"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ResilienceProperties.class).requestTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(context.getBean(ResilienceProperties.class).maxConnections()).isEqualTo(2);
                    assertThat(context.getBean(ProductCacheProperties.class).ttl()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(context.getBean(ProductCacheProperties.class).maxEntries()).isEqualTo(7);
                });
    }

    @Test
    void malformedHttpLoadsAreNotCachedAndTheNextRequestCanRecover() {
        var detailCalls = new AtomicInteger();
        runner(WebClient.builder().exchangeFunction(request -> {
            if (request.url().getPath().endsWith("/similarids")) { return Mono.just(json("[2]")); }
            return Mono.just(json(detailCalls.incrementAndGet() == 1 ? "{"
                    : "{\"id\":2,\"name\":\"Name\",\"price\":1,\"availability\":true}"));
        })).run(context -> {
            var useCase = context.getBean(GetSimilarProducts.class);
            StepVerifier.create(useCase.getSimilarProducts("source")).expectErrorSatisfies(error ->
                    assertThat(((UpstreamException) error).kind()).isEqualTo(UpstreamException.Kind.INVALID_PAYLOAD)).verify();
            assertThat(context.getBean(Cache.class).estimatedSize()).isZero();
            for (int i = 0; i < 2; i++) {
                StepVerifier.create(useCase.getSimilarProducts("source")).expectNextCount(1).verifyComplete();
            }
            assertThat(detailCalls.get()).isEqualTo(2);
        });
    }

    private static ApplicationContextRunner runner(WebClient.Builder builder) {
        return new ApplicationContextRunner().withUserConfiguration(ProductConfiguration.class)
                .withBean(WebClient.Builder.class, () -> builder).withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("upstream.base-url=http://example.invalid", "upstream.max-concurrency=4");
    }

    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build();
    }
}
