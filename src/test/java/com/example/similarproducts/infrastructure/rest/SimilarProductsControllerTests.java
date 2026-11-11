package com.example.similarproducts.infrastructure.rest;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import com.example.similarproducts.application.GetSimilarProducts;
import com.example.similarproducts.application.MissingProductException;
import com.example.similarproducts.domain.Product;
import com.example.similarproducts.infrastructure.http.UpstreamException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class SimilarProductsControllerTests {

    @Test
    void exposesTheExactRouteAndOnlyContractFieldsWithTheirJsonTypesInSimilarityOrder() {
        var client = client(id -> {
            assertThat(id).isEqualTo("source");
            return Mono.just(List.of(new Product("3", "Blazer", new BigDecimal("29.99"), false),
                    new Product("2", "Dress", new BigDecimal("19.99"), true)));
        });

        client.get().uri("/product/source/similar").exchange()
                .expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().consumeWith(result -> {
                    try {
                        var root = new ObjectMapper().readTree(result.getResponseBody());
                        assertThat(root.isArray()).isTrue();
                        assertThat(root.size()).isEqualTo(2);
                        assertThat(root.get(0).get("id").textValue()).isEqualTo("3");
                        assertThat(root.get(1).get("id").textValue()).isEqualTo("2");
                        root.forEach(product -> {
                            var names = new HashSet<String>();
                            product.fieldNames().forEachRemaining(names::add);
                            assertThat(names).containsExactlyInAnyOrder("id", "name", "price", "availability");
                            assertThat(product.get("id").isTextual()).isTrue();
                            assertThat(product.get("name").isTextual()).isTrue();
                            assertThat(product.get("price").isNumber()).isTrue();
                            assertThat(product.get("availability").isBoolean()).isTrue();
                        });
                        assertThat(root.get(0).get("availability").booleanValue()).isFalse();
                    } catch (java.io.IOException error) {
                        throw new AssertionError(error);
                    }
                });
    }

    @Test
    void returnsAnEmptyArray() {
        client(id -> Mono.just(List.of())).get().uri("/product/source/similar").exchange()
                .expectStatus().isOk().expectBody().json("[]", true);
    }

    @Test
    void passesADecodedStringIdToTheUseCaseWithoutRestrictingItToNumbers() {
        String sourceId = "A /?#%+é";
        client(id -> {
            assertThat(id).isEqualTo(sourceId);
            return Mono.just(List.of());
        }).get().uri("/product/{id}/similar", sourceId).exchange()
                .expectStatus().isOk().expectBody().json("[]", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/products/source/similar", "/product/source"})
    void doesNotAddOtherEndpoints(String path) {
        client(id -> Mono.just(List.of())).get().uri(path).exchange().expectStatus().isNotFound();
    }

    @ParameterizedTest
    @MethodSource("failures")
    void mapsErrorsWithoutExposingPayloadsExceptionNamesOrInternalMessages(RuntimeException failure, int status) {
        client(id -> Mono.error(failure)).get().uri("/product/source/similar").exchange()
                .expectStatus().isEqualTo(status).expectBody().isEmpty();
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new MissingProductException("private-id"), 404),
                Arguments.of(failure(UpstreamException.Kind.HTTP_STATUS, 500), 502),
                Arguments.of(failure(UpstreamException.Kind.HTTP_STATUS, 404), 502),
                Arguments.of(failure(UpstreamException.Kind.INVALID_PAYLOAD, 0), 502),
                Arguments.of(failure(UpstreamException.Kind.TRANSPORT, 0), 503),
                Arguments.of(new IllegalStateException("private implementation detail"), 500));
    }

    @Test
    void preservesFrameworkNotAcceptableStatus() {
        client(id -> Mono.just(List.of())).get().uri("/product/source/similar")
                .accept(MediaType.TEXT_PLAIN).exchange().expectStatus().isEqualTo(406);
    }

    private static UpstreamException failure(UpstreamException.Kind kind, int status) {
        return new UpstreamException(kind, status, "private upstream URL and payload",
                new IllegalStateException("secret cause"));
    }

    private static WebTestClient client(GetSimilarProducts useCase) {
        return WebTestClient.bindToController(new SimilarProductsController(useCase))
                .controllerAdvice(new ApiExceptionHandler()).build();
    }
}
