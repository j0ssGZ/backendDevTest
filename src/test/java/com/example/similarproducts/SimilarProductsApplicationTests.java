package com.example.similarproducts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "UPSTREAM_BASE_URL=http://localhost:3999")
class SimilarProductsApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Test
    void contextStartsWithUpstreamConfigurationAndServesHttp() {
        assertThat(environment.getProperty("upstream.base-url"))
                .isEqualTo("http://localhost:3999");

        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build()
                .get().uri("/__bootstrap_probe")
                .exchange()
                .expectStatus().isNotFound();
    }
}
