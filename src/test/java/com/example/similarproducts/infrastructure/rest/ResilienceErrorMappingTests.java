package com.example.similarproducts.infrastructure.rest;

import java.util.concurrent.TimeoutException;

import com.example.similarproducts.infrastructure.http.UpstreamException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class ResilienceErrorMappingTests {

    @ParameterizedTest
    @CsvSource({"TIMEOUT,504", "OVERLOADED,503", "HTTP_STATUS,502", "TRANSPORT,503", "INVALID_PAYLOAD,502"})
    void keepsResilienceFailuresDistinctAndTheirInternalDetailsPrivate(UpstreamException.Kind kind, int status) {
        var failure = new UpstreamException(kind, 0, "private connection or payload information",
                new TimeoutException("private cause"));
        WebTestClient.bindToController(new SimilarProductsController(id -> Mono.error(failure)))
                .controllerAdvice(new ApiExceptionHandler()).build().get().uri("/product/source/similar")
                .exchange().expectStatus().isEqualTo(status).expectBody().isEmpty();
    }
}
