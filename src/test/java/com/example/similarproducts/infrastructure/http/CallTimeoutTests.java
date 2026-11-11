package com.example.similarproducts.infrastructure.http;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ConnectTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import static org.assertj.core.api.Assertions.assertThat;

class CallTimeoutTests {

    @Test
    void boundsAnIndividualCallAndCancelsPendingResponseWork() {
        var pending = TestPublisher.<ClientResponse>create();
        StepVerifier.withVirtualTime(() -> new ProductApiClient(WebClient.builder()
                        .exchangeFunction(request -> pending.mono()).build(), new ObjectMapper(), Duration.ofSeconds(2))
                        .findById("2"))
                .thenAwait(Duration.ofSeconds(2)).expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(UpstreamException.class);
                    assertThat(((UpstreamException) error).kind()).isEqualTo(UpstreamException.Kind.TIMEOUT);
                    assertThat(error.getCause()).isInstanceOf(TimeoutException.class);
                }).verify();
        pending.assertCancelled();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 500})
    void boundsBothSuccessfulAndErrorResponseBodyConsumption(int status) {
        var body = TestPublisher.<org.springframework.core.io.buffer.DataBuffer>create();
        StepVerifier.withVirtualTime(() -> {
            var response = ClientResponse.create(HttpStatus.valueOf(status))
                    .header("Content-Type", "application/json").body(body.flux()).build();
            return new ProductApiClient(WebClient.builder().exchangeFunction(request -> Mono.just(response)).build(),
                    new ObjectMapper(), Duration.ofSeconds(2)).findSimilarIds("source");
        }).thenAwait(Duration.ofSeconds(2)).expectErrorSatisfies(error ->
                assertThat(((UpstreamException) error).kind()).isEqualTo(UpstreamException.Kind.TIMEOUT)).verify();
        body.assertCancelled();
    }

    @Test
    void userCancellationCancelsTheHttpSubscription() {
        var pending = TestPublisher.<ClientResponse>create();
        var client = new ProductApiClient(WebClient.builder().exchangeFunction(request -> pending.mono()).build(),
                new ObjectMapper(), Duration.ofSeconds(2));
        StepVerifier.create(client.findSimilarIds("source")).thenCancel().verify();
        pending.assertCancelled();
    }

    @Test
    void successfulResponsesBeforeTheDeadlineAreUnchanged() {
        StepVerifier.withVirtualTime(() -> {
            var response = ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body("[2]").build();
            return new ProductApiClient(WebClient.builder().exchangeFunction(request ->
                            Mono.delay(Duration.ofSeconds(1)).thenReturn(response)).build(),
                    new ObjectMapper(), Duration.ofSeconds(2)).findSimilarIds("source");
        }).thenAwait(Duration.ofSeconds(1)).expectNext(List.of("2")).verifyComplete();
    }

    @Test
    void distinguishesATcpConnectTimeoutFromARefusedConnectionWithoutUsingAnExternalBlackhole() {
        var cause = new WebClientRequestException(new ConnectTimeoutException("connect timed out"),
                HttpMethod.GET, URI.create("http://example.invalid"), HttpHeaders.EMPTY);
        var client = new ProductApiClient(WebClient.builder().exchangeFunction(request -> Mono.error(cause)).build(),
                new ObjectMapper(), Duration.ofSeconds(2));
        StepVerifier.create(client.findSimilarIds("source")).expectErrorSatisfies(error -> {
            assertThat(((UpstreamException) error).kind()).isEqualTo(UpstreamException.Kind.TIMEOUT);
            assertThat(error.getCause()).isSameAs(cause);
        }).verify();
    }
}
