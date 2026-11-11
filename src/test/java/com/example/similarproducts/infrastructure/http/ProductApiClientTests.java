package com.example.similarproducts.infrastructure.http;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.example.similarproducts.application.GetSimilarProductsService;
import com.example.similarproducts.infrastructure.LocalHttpStub;
import com.example.similarproducts.infrastructure.LocalHttpStub.Reply;
import com.example.similarproducts.infrastructure.rest.ApiExceptionHandler;
import com.example.similarproducts.infrastructure.rest.SimilarProductsController;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ProductApiClientTests {

    private static final String PRODUCT =
            "{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.9900,\"availability\":true}";

    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private LocalHttpStub stub;
    private ProductApiClient client;

    @BeforeEach
    void startStub() {
        stub = new LocalHttpStub(path -> reply.get());
        client = new ProductApiClient(WebClient.builder().baseUrl(stub.baseUrl()).build(), mapper);
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @ParameterizedTest
    @MethodSource("validIds")
    void decodesNumericAndStringIdsWithoutGlobalCoercion(String body, List<String> expected) {
        reply.set(Reply.json(body));

        StepVerifier.create(client.findSimilarIds("source"))
                .expectNext(expected).verifyComplete();

        assertThat(stub.requests()).containsExactly("/product/source/similarids");
        assertThat(mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isFalse();
    }

    static Stream<Arguments> validIds() {
        return Stream.of(
                Arguments.of("[2,3,4]", List.of("2", "3", "4")),
                Arguments.of("[\"2\",\"003\",\"sku-a\",\" \"]", List.of("2", "003", "sku-a", " ")),
                Arguments.of("[2.0,3e0,1000000000000000000000000]",
                        List.of("2", "3", "1000000000000000000000000")),
                Arguments.of("[]", List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[null]", "[{}]", "[[]]", "[true]", "[1.25]", "[\"\"]",
            "null", "{}", "\"id\"", "[1,", "[1] [2]", ""})
    void rejectsInvalidSimilarIdsAndMalformedPayloads(String body) {
        reply.set(Reply.json(body));

        StepVerifier.create(client.findSimilarIds("source"))
                .expectErrorSatisfies(ProductApiClientTests::assertInvalidPayload).verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"2\"", "2", "2.0", "2e0"})
    void decodesAndMatchesNormalizedDetailIdentity(String id) {
        reply.set(Reply.json(PRODUCT.replace("\"id\":\"2\"", "\"id\":" + id)));

        StepVerifier.create(client.findById("2"))
                .assertNext(product -> {
                    assertThat(product.id()).isEqualTo("2");
                    assertThat(product.name()).isEqualTo("Dress");
                    assertThat(product.price().toPlainString()).isEqualTo("19.9900");
                    assertThat(product.availability()).isTrue();
                }).verifyComplete();
        assertThat(stub.requests()).containsExactly("/product/2");
    }

    @Test
    void preservesArbitraryDecimalPrecision() {
        String price = "12345678901234567890.123456789012345678901234567890";
        reply.set(Reply.json(PRODUCT.replace("19.9900", price)));

        StepVerifier.create(client.findById("2"))
                .assertNext(product -> assertThat(product.price().toPlainString()).isEqualTo(price))
                .verifyComplete();
    }

    @ParameterizedTest
    @MethodSource("invalidDetails")
    void rejectsMissingNullOrIncorrectlyTypedRequiredFields(String body) {
        reply.set(Reply.json(body));

        StepVerifier.create(client.findById("2"))
                .expectErrorSatisfies(ProductApiClientTests::assertInvalidPayload).verify();
    }

    static Stream<String> invalidDetails() {
        return Stream.of(
                PRODUCT.replace("\"id\":\"2\",", ""),
                PRODUCT.replace("\"id\":\"2\"", "\"id\":null"),
                PRODUCT.replace("\"id\":\"2\"", "\"id\":true"),
                PRODUCT.replace("\"id\":\"2\"", "\"id\":{}"),
                PRODUCT.replace("\"id\":\"2\"", "\"id\":[]"),
                PRODUCT.replace("\"id\":\"2\"", "\"id\":2.5"),
                PRODUCT.replace("\"name\":\"Dress\",", ""),
                PRODUCT.replace("\"name\":\"Dress\"", "\"name\":null"),
                PRODUCT.replace("\"name\":\"Dress\"", "\"name\":123"),
                PRODUCT.replace("\"name\":\"Dress\"", "\"name\":\"\""),
                PRODUCT.replace("\"price\":19.9900,", ""),
                PRODUCT.replace("19.9900", "null"),
                PRODUCT.replace("19.9900", "\"19.99\""),
                PRODUCT.replace("19.9900", "true"),
                PRODUCT.replace(",\"availability\":true", ""),
                PRODUCT.replace("\"availability\":true", "\"availability\":null"),
                PRODUCT.replace("\"availability\":true", "\"availability\":\"true\""),
                PRODUCT.replace("\"availability\":true", "\"availability\":1"),
                PRODUCT.replace("\"id\":\"2\"", "\"id\":\"2\",\"id\":\"3\""),
                "[]", "null", "{", PRODUCT + " {}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "002"})
    void rejectsMismatchingIdentityWithoutRewritingTheProduct(String requestedId) {
        reply.set(Reply.json(PRODUCT));

        StepVerifier.create(client.findById(requestedId))
                .expectErrorSatisfies(ProductApiClientTests::assertInvalidPayload).verify();
    }

    @Test
    void encodesEachIdAsOnePathVariableForBothOperations() throws Exception {
        String id = "A /?#%+é";
        reply.set(Reply.json("[]"));
        StepVerifier.create(client.findSimilarIds(id)).expectNext(List.of()).verifyComplete();
        reply.set(Reply.json(PRODUCT.replace("\"id\":\"2\"", "\"id\":" + mapper.writeValueAsString(id))));
        StepVerifier.create(client.findById(id)).expectNextCount(1).verifyComplete();

        assertThat(stub.requests()).containsExactly(
                "/product/A%20%2F%3F%23%25%2B%C3%A9/similarids",
                "/product/A%20%2F%3F%23%25%2B%C3%A9");
    }

    @Test
    void representsMissingDetailAsEmptyForTheApplicationPolicy() {
        reply.set(new Reply(404, "application/json", "{\"message\":\"Product not found\"}"));

        StepVerifier.create(client.findById("5")).verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 301, 400, 404, 500, 503})
    void preservesUnexpectedIdLookupStatusAndOriginalCause(int status) {
        reply.set(new Reply(status, "text/plain", "private upstream failure"));

        StepVerifier.create(client.findSimilarIds("source"))
                .expectErrorSatisfies(error -> {
                    var upstream = (UpstreamException) error;
                    assertThat(upstream.kind()).isEqualTo(UpstreamException.Kind.HTTP_STATUS);
                    assertThat(upstream.upstreamStatus()).isEqualTo(status);
                    assertThat(upstream.getCause()).isInstanceOf(WebClientResponseException.class);
                }).verify();
    }

    @Test
    void distinguishesDetailServerFailureFromAbsence() {
        reply.set(new Reply(500, "application/json", ""));

        StepVerifier.create(client.findById("6"))
                .expectErrorSatisfies(error -> {
                    var upstream = (UpstreamException) error;
                    assertThat(upstream.kind()).isEqualTo(UpstreamException.Kind.HTTP_STATUS);
                    assertThat(upstream.upstreamStatus()).isEqualTo(500);
                }).verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "text/html", "invalid-type", ""})
    void rejectsSuccessfulResponsesWithoutJsonContentType(String contentType) {
        reply.set(new Reply(200, contentType.isEmpty() ? null : contentType, "[]"));

        StepVerifier.create(client.findSimilarIds("source"))
                .expectErrorSatisfies(ProductApiClientTests::assertInvalidPayload).verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json;charset=utf-8", "application/vnd.products+json"})
    void acceptsJsonCharsetParametersAndStructuredJsonMediaTypes(String contentType) {
        reply.set(new Reply(200, contentType, "[2]"));

        StepVerifier.create(client.findSimilarIds("source")).expectNext(List.of("2")).verifyComplete();
    }

    @Test
    void preservesConnectionFailureCause() {
        stub.close();

        StepVerifier.create(client.findSimilarIds("source"))
                .expectErrorSatisfies(error -> {
                    var upstream = (UpstreamException) error;
                    assertThat(upstream.kind()).isEqualTo(UpstreamException.Kind.TRANSPORT);
                    assertThat(upstream.getCause()).isInstanceOf(WebClientRequestException.class);
                }).verify();
    }

    @Test
    void returnsSanitizedServiceUnavailableWhenTheConfiguredHttpServerIsDisconnected() {
        stub.close();
        var useCase = new GetSimilarProductsService(client, client, 2);
        WebTestClient.bindToController(new SimilarProductsController(useCase))
                .controllerAdvice(new ApiExceptionHandler()).build()
                .get().uri("/product/source/similar").exchange()
                .expectStatus().isEqualTo(503).expectBody().isEmpty();
    }

    private static void assertInvalidPayload(Throwable error) {
        assertThat(error).isInstanceOf(UpstreamException.class);
        assertThat(((UpstreamException) error).kind()).isEqualTo(UpstreamException.Kind.INVALID_PAYLOAD);
    }
}
