package com.example.similarproducts.infrastructure.http;

import java.io.IOException;
import java.util.List;

import com.example.similarproducts.application.port.out.ProductDetails;
import com.example.similarproducts.application.port.out.SimilarProductIds;
import com.example.similarproducts.domain.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

public final class ProductApiClient implements SimilarProductIds, ProductDetails {

    private final WebClient client;
    private final UpstreamPayloadDecoder decoder;

    public ProductApiClient(WebClient client, ObjectMapper mapper) {
        this.client = client;
        this.decoder = new UpstreamPayloadDecoder(mapper);
    }

    @Override
    public Mono<List<String>> findSimilarIds(String productId) {
        return get("/product/{productId}/similarids", productId, false).map(decoder::similarIds);
    }

    @Override
    public Mono<Product> findById(String productId) {
        return get("/product/{productId}", productId, true).map(body -> decoder.product(body, productId));
    }

    private Mono<byte[]> get(String path, String productId, boolean allowMissingDetail) {
        return client.get().uri(path, productId).accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> {
                    if (allowMissingDetail && response.statusCode().equals(HttpStatus.NOT_FOUND)) {
                        return response.releaseBody().then(Mono.empty());
                    }
                    if (!response.statusCode().equals(HttpStatus.OK)) {
                        return response.createException().flatMap(cause -> Mono.error(new UpstreamException(
                                UpstreamException.Kind.HTTP_STATUS, response.statusCode().value(),
                                "Unexpected upstream HTTP status", cause)));
                    }
                    return jsonBody(response);
                })
                .onErrorMap(DataBufferLimitException.class, cause -> new UpstreamException(
                        UpstreamException.Kind.INVALID_PAYLOAD, 0, "Upstream payload exceeds decoding limit", cause))
                .onErrorMap(InvalidMediaTypeException.class, cause -> new UpstreamException(
                        UpstreamException.Kind.INVALID_PAYLOAD, 0, "Invalid upstream content type", cause))
                .onErrorMap(WebClientRequestException.class, this::transportFailure)
                .onErrorMap(WebClientResponseException.class, this::transportFailure)
                .onErrorMap(IOException.class, this::transportFailure);
    }

    private Mono<byte[]> jsonBody(ClientResponse response) {
        boolean json = response.headers().contentType().map(type -> "application".equals(type.getType())
                && ("json".equals(type.getSubtype()) || type.getSubtype().endsWith("+json"))).orElse(false);
        if (!json) {
            return Mono.error(UpstreamException.invalid("Upstream success response must have a JSON content type"));
        }
        return response.bodyToMono(byte[].class)
                .switchIfEmpty(Mono.error(() -> UpstreamException.invalid("Empty upstream JSON payload")));
    }

    private UpstreamException transportFailure(Throwable cause) {
        return new UpstreamException(UpstreamException.Kind.TRANSPORT, 0, "Upstream transport failed", cause);
    }
}
