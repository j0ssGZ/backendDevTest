package com.example.similarproducts.infrastructure.resilience;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.example.similarproducts.application.GetSimilarProducts;
import com.example.similarproducts.application.GetSimilarProductsService;
import com.example.similarproducts.application.MissingProductException;
import com.example.similarproducts.domain.Product;
import com.example.similarproducts.infrastructure.http.UpstreamException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineGetSimilarProductsTests {

    @Test
    void deadlineCoversIdsAndTheWholeDetailSequenceWithoutResettingPerCall() {
        var requested = new ArrayList<String>();
        var cancelled = new ArrayList<String>();
        StepVerifier.withVirtualTime(() -> {
            var service = new GetSimilarProductsService(
                    id -> Mono.delay(Duration.ofSeconds(2)).thenReturn(List.of("1", "2", "3")),
                    id -> {
                        requested.add(id);
                        return Mono.delay(Duration.ofSeconds(4)).thenReturn(product(id))
                                .doOnCancel(() -> cancelled.add(id));
                    }, 1);
            return new DeadlineGetSimilarProducts(service, Duration.ofSeconds(8)).getSimilarProducts("source");
        }).thenAwait(Duration.ofSeconds(8)).expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(UpstreamException.class);
            assertThat(((UpstreamException) error).kind()).isEqualTo(UpstreamException.Kind.TIMEOUT);
        }).verify();
        assertThat(requested).containsExactly("1", "2");
        assertThat(cancelled).contains("2");
    }

    @Test
    void cancelsAllActiveDetailsWithoutStartingQueuedOnesOrEmittingAPartialList() {
        var first = TestPublisher.<Product>create();
        var second = TestPublisher.<Product>create();
        var requested = new ArrayList<String>();
        StepVerifier.withVirtualTime(() -> {
            var service = new GetSimilarProductsService(id -> Mono.just(List.of("1", "2", "3")), id -> {
                requested.add(id); return id.equals("1") ? first.mono() : second.mono();
            }, 2);
            return new DeadlineGetSimilarProducts(service, Duration.ofSeconds(2)).getSimilarProducts("source");
        }).thenAwait(Duration.ofSeconds(2)).expectError(UpstreamException.class).verify();
        first.assertCancelled();
        second.assertCancelled();
        assertThat(requested).containsExactly("1", "2");
    }

    @Test
    void cancelsThePendingInitialLookup() {
        var pending = TestPublisher.<List<String>>create();
        StepVerifier.withVirtualTime(() -> new DeadlineGetSimilarProducts(new GetSimilarProductsService(
                        id -> pending.mono(), id -> { throw new AssertionError("Unexpected detail"); }, 1), Duration.ofSeconds(1))
                        .getSimilarProducts("source"))
                .thenAwait(Duration.ofSeconds(1)).expectError(UpstreamException.class).verify();
        pending.assertCancelled();
    }

    @Test
    void normalRequestsKeepTheirOrderedValues() {
        StepVerifier.withVirtualTime(() -> new DeadlineGetSimilarProducts(
                        id -> Mono.delay(Duration.ofSeconds(1)).thenReturn(List.of(product("2"), product("1"))),
                        Duration.ofSeconds(2)).getSimilarProducts("source"))
                .thenAwait(Duration.ofSeconds(1)).expectNext(List.of(product("2"), product("1"))).verifyComplete();
    }

    @Test
    void preservesMissingDetailAndOriginalErrorsRatherThanTreatingThemAsTimeouts() {
        var failure = new MissingProductException("2");
        GetSimilarProducts service = new DeadlineGetSimilarProducts(id -> Mono.error(failure), Duration.ofSeconds(2));
        StepVerifier.create(service.getSimilarProducts("source"))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure)).verify();
    }

    private static Product product(String id) {
        return new Product(id, "Name", BigDecimal.ONE, true);
    }
}
