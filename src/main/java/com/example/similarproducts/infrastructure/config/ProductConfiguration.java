package com.example.similarproducts.infrastructure.config;

import com.example.similarproducts.application.GetSimilarProducts;
import com.example.similarproducts.application.GetSimilarProductsService;
import com.example.similarproducts.infrastructure.http.ProductApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UpstreamProperties.class)
public class ProductConfiguration {

    @Bean
    ProductApiClient productApiClient(WebClient.Builder builder, ObjectMapper mapper, UpstreamProperties properties) {
        var uriFactory = new DefaultUriBuilderFactory(properties.baseUrl().toString());
        uriFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.TEMPLATE_AND_VALUES);
        return new ProductApiClient(builder.uriBuilderFactory(uriFactory).build(), mapper);
    }

    @Bean
    GetSimilarProducts getSimilarProducts(ProductApiClient client, UpstreamProperties properties) {
        return new GetSimilarProductsService(client, client, properties.maxConcurrency());
    }
}
