package com.example.similarproducts.infrastructure.config;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.example.similarproducts.infrastructure.http.ProductApiClient;
import com.example.similarproducts.infrastructure.http.UpstreamException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class NettyResourceLimitsTests {

    @ParameterizedTest
    @ValueSource(ints = {200, 500})
    void actualResponseReadTimeoutCancelsTheChannelIncludingAnErrorBody(int status) throws Exception {
        try (var fixture = new Fixture(status, Duration.ofMillis(300), Duration.ofSeconds(3), false)) {
            StepVerifier.create(fixture.client.findSimilarIds("held"))
                    .expectErrorSatisfies(error -> assertKind(error, UpstreamException.Kind.TIMEOUT))
                    .verify(Duration.ofSeconds(5));
            assertThat(fixture.closed.await(5, TimeUnit.SECONDS)).isTrue();
            fixture.verifyFastCall();
        }
    }

    @Test
    void acquisitionTimeoutRemovesTheWaiterAndCancellationReleasesTheActiveSlot() throws Exception {
        try (var fixture = new Fixture(200, Duration.ofSeconds(10), Duration.ofMillis(250), false)) {
            Disposable active = fixture.startActiveRequest();
            try {
                StepVerifier.create(fixture.client.findSimilarIds("waiting"))
                        .expectErrorSatisfies(error -> assertKind(error, UpstreamException.Kind.TIMEOUT))
                        .verify(Duration.ofSeconds(5));
                assertThat(fixture.requests).containsExactly("/product/held/similarids");
                assertThat(fixture.activeFailure.get()).isNull();
            } finally {
                active.dispose();
            }
            assertThat(fixture.closed.await(5, TimeUnit.SECONDS)).isTrue();
            fixture.verifyFastCall();
        }
    }

    @Test
    void fullPendingQueueRejectsAdditionalWorkAndCancelledWaitersDoNotLeak() throws Exception {
        try (var fixture = new Fixture(200, Duration.ofSeconds(10), Duration.ofSeconds(5), true)) {
            Disposable active = fixture.startActiveRequest();
            fixture.observePending.set(true);
            Disposable waiting = fixture.client.findSimilarIds("waiting").subscribe(ignored -> {}, fixture.activeFailure::set);
            try {
                // The production pool configuration is retained; a test timer hook signals actual queue admission.
                assertThat(fixture.queued.await(5, TimeUnit.SECONDS)).isTrue();
                StepVerifier.create(fixture.client.findSimilarIds("rejected"))
                        .expectErrorSatisfies(error -> assertKind(error, UpstreamException.Kind.OVERLOADED))
                        .verify(Duration.ofSeconds(5));
                assertThat(fixture.requests).containsExactly("/product/held/similarids");
            } finally {
                waiting.dispose();
                active.dispose();
            }
            assertThat(fixture.closed.await(5, TimeUnit.SECONDS)).isTrue();
            fixture.verifyFastCall();
            assertThat(fixture.activeFailure.get()).isNull();
        }
    }

    private static void assertKind(Throwable error, UpstreamException.Kind kind) {
        assertThat(error).isInstanceOf(UpstreamException.class);
        assertThat(((UpstreamException) error).kind()).isEqualTo(kind);
        assertThat(error.getCause()).isNotNull();
    }

    private static final class Fixture implements AutoCloseable {
        private final CountDownLatch received = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final CountDownLatch queued = new CountDownLatch(1);
        private final AtomicBoolean observePending = new AtomicBoolean();
        private final AtomicReference<Throwable> activeFailure = new AtomicReference<>();
        private final ConcurrentLinkedQueue<String> requests = new ConcurrentLinkedQueue<>();
        private final DisposableServer server;
        private final ConnectionProvider provider;
        private final ProductApiClient client;

        private Fixture(int status, Duration responseTimeout, Duration acquireTimeout, boolean traceQueue) {
            server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
                requests.add(request.uri());
                response.header("Content-Type", "application/json");
                if (request.uri().contains("/fast/")) {
                    return response.sendString(Mono.just("[]"));
                }
                request.withConnection(connection -> connection.onDispose(closed::countDown));
                received.countDown();
                response.status(status);
                return response.sendHeaders().then(response.sendString(Mono.never()).then());
            }).bindNow();
            var properties = new ResilienceProperties(Duration.ofSeconds(1), acquireTimeout, responseTimeout,
                    Duration.ofSeconds(10), Duration.ofSeconds(20), 1, 1, Duration.ofSeconds(30), Duration.ofSeconds(15));
            var configuration = new ProductConfiguration();
            var original = configuration.upstreamConnectionProvider(properties);
            if (traceQueue) {
                var builder = original.mutate();
                original.dispose();
                provider = builder.pendingAcquireTimer((task, delay) -> {
                    Disposable timer = Schedulers.parallel().schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
                    if (observePending.get()) {
                        queued.countDown();
                    }
                    return timer;
                }).build();
            } else {
                provider = original;
            }
            client = new ProductApiClient(WebClient.builder().baseUrl("http://127.0.0.1:" + server.port())
                    .clientConnector(new ReactorClientHttpConnector(configuration.upstreamHttpClient(provider, properties)))
                    .build(), new ObjectMapper(), properties.callTimeout());
        }

        private Disposable startActiveRequest() throws InterruptedException {
            Disposable active = client.findSimilarIds("held").subscribe(ignored -> {}, activeFailure::set);
            if (!received.await(5, TimeUnit.SECONDS)) {
                active.dispose();
                throw new AssertionError("The active HTTP request did not reach the local server");
            }
            return active;
        }

        private void verifyFastCall() {
            StepVerifier.create(client.findSimilarIds("fast")).expectNext(List.of()).expectComplete()
                    .verify(Duration.ofSeconds(5));
        }

        @Override
        public void close() {
            provider.disposeLater().block(Duration.ofSeconds(5));
            server.disposeNow();
            assertThat(provider.isDisposed()).isTrue();
        }
    }
}
