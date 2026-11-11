package com.example.similarproducts.infrastructure.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.Map;

import com.example.similarproducts.application.GetSimilarProducts;
import com.example.similarproducts.application.port.out.ProductDetails;
import com.example.similarproducts.application.port.out.SimilarProductIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ProductConfigurationTests {

    @Test
    void wiresBothPortsAndAppliesConfiguredUrlAndConcurrencyWithoutTimingAssumptions() {
        var requests = new ArrayList<String>();
        Map<String, TestPublisher<ClientResponse>> details = Map.of(
                "1", TestPublisher.create(), "2", TestPublisher.create(), "3", TestPublisher.create());
        var builder = WebClient.builder().exchangeFunction(request -> {
            requests.add(request.url().toString());
            if (request.url().getPath().endsWith("/similarids")) {
                return Mono.just(json("[1,2,3]"));
            }
            return details.get(request.url().getPath().substring(request.url().getPath().lastIndexOf('/') + 1)).mono();
        });

        new ApplicationContextRunner().withUserConfiguration(ProductConfiguration.class)
                .withBean(WebClient.Builder.class, () -> builder)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("upstream.base-url=http://configured.invalid/base", "upstream.max-concurrency=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SimilarProductIds.class)).isSameAs(context.getBean(ProductDetails.class));
                    StepVerifier.create(context.getBean(GetSimilarProducts.class).getSimilarProducts("source"))
                            .then(() -> {
                                assertThat(requests).containsExactly("http://configured.invalid/base/product/source/similarids",
                                        "http://configured.invalid/base/product/1", "http://configured.invalid/base/product/2");
                                details.get("1").assertSubscribers(1);
                                details.get("2").assertSubscribers(1);
                                details.get("3").assertNoSubscribers();
                                details.get("1").emit(product("1"));
                                details.get("3").assertSubscribers(1);
                                details.get("3").emit(product("3"));
                                details.get("2").emit(product("2"));
                            })
                            .assertNext(products -> assertThat(products).extracting(product -> product.id())
                                    .containsExactly("1", "2", "3"))
                            .verifyComplete();
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsInvalidConfiguredConcurrencyAtStartup(int concurrency) {
        new ApplicationContextRunner().withUserConfiguration(ProductConfiguration.class)
                .withBean(WebClient.Builder.class, WebClient::builder)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("upstream.base-url=http://localhost:3001", "upstream.max-concurrency=" + concurrency)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative", "ftp://localhost", "http://localhost?query=1", "http://localhost#fragment"})
    void rejectsInvalidBaseUrls(String url) {
        assertThatIllegalArgumentException().isThrownBy(() -> new UpstreamProperties(URI.create(url), 4));
    }

    private static ClientResponse product(String id) {
        return json("{\"id\":\"" + id + "\",\"name\":\"Name\",\"price\":1,\"availability\":true}");
    }

    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build();
    }
}
