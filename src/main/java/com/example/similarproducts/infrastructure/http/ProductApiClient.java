package com.example.similarproducts.infrastructure.http;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import com.example.similarproducts.application.port.out.ProductDetails;
import com.example.similarproducts.application.port.out.SimilarProductIds;
import com.example.similarproducts.domain.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ConnectTimeoutException;
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
    private final Duration callTimeout;

    public ProductApiClient(WebClient client, ObjectMapper mapper) {
        this(client, mapper, Duration.ofSeconds(7));
    }

    public ProductApiClient(WebClient client, ObjectMapper mapper, Duration callTimeout) {
        this.client = client;
        this.decoder = new UpstreamPayloadDecoder(mapper);
        this.callTimeout = callTimeout;
    }

    @Override
    public Mono<List<String>> findSimilarIds(String productId) {
        return bounded(get("/product/{productId}/similarids", productId, false).map(decoder::similarIds));
    }

    @Override
    public Mono<Product> findById(String productId) {
        return bounded(get("/product/{productId}", productId, true).map(body -> decoder.product(body, productId)));
    }

    private Mono<byte[]> get(String path, String productId, boolean allowMissingDetail) {
        return client.get().uri(path, productId).accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> {
                    if (allowMissingDetail && response.statusCode().equals(HttpStatus.NOT_FOUND)) {
                        return response.releaseBody().then(Mono.empty());
                    }
                    if (!response.statusCode().equals(HttpStatus.OK)) {
                        // Do not let createException() suppress a timeout while reading an error body.
                        return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(body ->
                                Mono.error(new UpstreamException(UpstreamException.Kind.HTTP_STATUS,
                                        response.statusCode().value(), "Unexpected upstream HTTP status",
                                        WebClientResponseException.create(response.statusCode().value(),
                                                "Unexpected upstream HTTP status", response.headers().asHttpHeaders(), body, null))));
                    }
                    return jsonBody(response);
                });
    }

    private <T> Mono<T> bounded(Mono<T> operation) {
        return operation.timeout(callTimeout).onErrorMap(this::classifyFailure);
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

    private Throwable classifyFailure(Throwable error) {
        if (error instanceof UpstreamException) {
            return error;
        }
        if (error instanceof DataBufferLimitException || error instanceof InvalidMediaTypeException) {
            return new UpstreamException(UpstreamException.Kind.INVALID_PAYLOAD, 0, "Invalid upstream payload", error);
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof io.netty.handler.timeout.TimeoutException
                    || cause instanceof ConnectTimeoutException || cause instanceof SocketTimeoutException) {
                return new UpstreamException(UpstreamException.Kind.TIMEOUT, 0, "Upstream deadline exceeded", error);
            }
            // Reactor Netty shades these internal pool exceptions. Avoid importing its private package.
            if (cause.getClass().getSimpleName().equals("PoolAcquirePendingLimitException")
                    || cause.getClass().getSimpleName().equals("PoolShutdownException")) {
                return new UpstreamException(UpstreamException.Kind.OVERLOADED, 0, "Upstream pool unavailable", error);
            }
        }
        if (error instanceof WebClientRequestException || error instanceof WebClientResponseException
                || error instanceof IOException) {
            return new UpstreamException(UpstreamException.Kind.TRANSPORT, 0, "Upstream transport failed", error);
        }
        return error;
    }
}
