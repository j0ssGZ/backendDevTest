package com.example.similarproducts.infrastructure;

import com.example.similarproducts.infrastructure.LocalHttpStub.Reply;
import com.example.similarproducts.infrastructure.config.UpstreamProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "UPSTREAM_MAX_CONCURRENCY=2")
class SimilarProductsIntegrationTests {

    private static final LocalHttpStub UPSTREAM = new LocalHttpStub(path -> switch (path) {
        case "/product/source/similarids" -> Reply.json("[2,\"3\",4]");
        case "/product/empty/similarids" -> Reply.json("[]");
        case "/product/missing/similarids" -> Reply.json("[5]");
        case "/product/error/similarids" -> Reply.json("[6]");
        case "/product/invalid/similarids" -> Reply.json("[2.5]");
        case "/product/malformed/similarids" -> Reply.json("[");
        case "/product/mismatch/similarids" -> Reply.json("[7]");
        case "/product/2" -> Reply.json("{\"id\":2,\"name\":\"Dress\",\"price\":19.99,\"availability\":true,\"private\":\"hidden\"}");
        case "/product/3" -> Reply.json("{\"id\":\"3\",\"name\":\"Blazer\",\"price\":29.99,\"availability\":false}");
        case "/product/4" -> Reply.json("{\"id\":\"4\",\"name\":\"Boots\",\"price\":39.99,\"availability\":true}");
        case "/product/5" -> new Reply(404, "application/json", "{\"message\":\"Product not found\"}");
        case "/product/6" -> new Reply(500, "text/plain", "private server information");
        case "/product/7" -> Reply.json("{\"id\":8,\"name\":\"Other\",\"price\":1,\"availability\":true}");
        default -> new Reply(404, "text/plain", "Not Found");
    });

    @LocalServerPort
    private int port;

    @Autowired
    private UpstreamProperties properties;

    @DynamicPropertySource
    static void overrideUpstream(DynamicPropertyRegistry registry) {
        registry.add("UPSTREAM_BASE_URL", UPSTREAM::baseUrl);
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.close();
    }

    @Test
    void returnsAnExactContractResponseThroughRealHttpAdaptersAndConfiguredUpstream() {
        assertThat(properties.baseUrl().toString()).isEqualTo(UPSTREAM.baseUrl());
        assertThat(properties.maxConcurrency()).isEqualTo(2);
        client().get().uri("/product/source/similar").exchange()
                .expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().json("""
                        [{"id":"2","name":"Dress","price":19.99,"availability":true},
                         {"id":"3","name":"Blazer","price":29.99,"availability":false},
                         {"id":"4","name":"Boots","price":39.99,"availability":true}]
                        """, true);
        assertThat(UPSTREAM.requests()).contains("/product/source/similarids", "/product/2", "/product/3", "/product/4");
    }

    @Test
    void returnsAnEmptyArrayForValidEmptyUpstreamSimilarities() {
        client().get().uri("/product/empty/similar").exchange()
                .expectStatus().isOk().expectBody().json("[]", true);
    }

    @ParameterizedTest
    @CsvSource({"missing,404", "error,502", "invalid,502", "malformed,502", "unknown,502", "mismatch,502"})
    void returnsSanitizedErrorsForUpstreamFailures(String id, int status) {
        client().get().uri("/product/{id}/similar", id).exchange()
                .expectStatus().isEqualTo(status).expectBody().isEmpty();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }
}
