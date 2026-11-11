package com.example.similarproducts.infrastructure.config;

import com.example.similarproducts.application.GetSimilarProducts;
import com.example.similarproducts.application.GetSimilarProductsService;
import com.example.similarproducts.domain.Product;
import com.example.similarproducts.infrastructure.cache.CachingProductDetails;
import com.example.similarproducts.infrastructure.http.ProductApiClient;
import com.example.similarproducts.infrastructure.resilience.DeadlineGetSimilarProducts;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({UpstreamProperties.class, ResilienceProperties.class, ProductCacheProperties.class})
public class ProductConfiguration {

    @Bean
    ProductApiClient productApiClient(WebClient.Builder builder, ObjectMapper mapper, UpstreamProperties properties,
            HttpClient upstreamHttpClient, ResilienceProperties resilience) {
        var uriFactory = new DefaultUriBuilderFactory(properties.baseUrl().toString());
        uriFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.TEMPLATE_AND_VALUES);
        return new ProductApiClient(builder.uriBuilderFactory(uriFactory)
                .clientConnector(new ReactorClientHttpConnector(upstreamHttpClient)).build(), mapper, resilience.callTimeout());
    }

    @Bean
    GetSimilarProducts getSimilarProducts(ProductApiClient client, UpstreamProperties properties,
            Cache<String, Product> productDetailsCache, ProductCacheProperties caching, ResilienceProperties resilience) {
        var details = caching.enabled() ? new CachingProductDetails(client, productDetailsCache) : client;
        return new DeadlineGetSimilarProducts(new GetSimilarProductsService(client, details, properties.maxConcurrency()),
                resilience.requestTimeout());
    }

    @Bean(destroyMethod = "dispose")
    ConnectionProvider upstreamConnectionProvider(ResilienceProperties properties) {
        return ConnectionProvider.builder("product-upstream").maxConnections(properties.maxConnections())
                .pendingAcquireMaxCount(properties.maxPendingAcquisitions()).pendingAcquireTimeout(properties.acquireTimeout())
                .maxIdleTime(properties.maxIdleTime()).evictInBackground(properties.evictionInterval()).build();
    }

    @Bean
    HttpClient upstreamHttpClient(ConnectionProvider upstreamConnectionProvider, ResilienceProperties properties) {
        return HttpClient.create(upstreamConnectionProvider).disableRetry(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.connectTimeout().toMillis()))
                .responseTimeout(properties.responseTimeout());
    }

    @Bean(destroyMethod = "invalidateAll")
    Cache<String, Product> productDetailsCache(ProductCacheProperties properties) {
        return Caffeine.newBuilder().maximumSize(properties.maxEntries()).expireAfterWrite(properties.ttl())
                .executor(Runnable::run).build();
    }
}
