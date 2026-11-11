package com.example.similarproducts.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.similarproducts.application.port.out.ProductDetails;
import com.example.similarproducts.application.port.out.SimilarProductIds;
import com.example.similarproducts.domain.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class GetSimilarProductsServiceTests {

    @Test
    void retrievesAllSimilarDetailsIncludingUnavailableProducts() {
        var products = List.of(product("2"), product("3"), product("4"));
        SimilarProductIds ids = sourceId -> {
            assertThat(sourceId).isEqualTo("1");
            return Mono.just(List.of("2", "3", "4"));
        };
        ProductDetails details = id -> Mono.just(products.stream()
                .filter(product -> product.id().equals(id)).findFirst().orElseThrow());
        GetSimilarProducts useCase = new GetSimilarProductsService(ids, details, 2);

        StepVerifier.create(useCase.getSimilarProducts("1"))
                .expectNext(products)
                .verifyComplete();
    }

    @Test
    void returnsAnEmptyCollectionWithoutFetchingDetailsForAnEmptyIdList() {
        var service = new GetSimilarProductsService(id -> Mono.just(List.of()), noDetails(), 2);

        StepVerifier.create(service.getSimilarProducts("1"))
                .expectNext(List.of())
                .verifyComplete();
    }

    @Test
    void preservesSimilarityRatherThanSortingById() {
        var service = new GetSimilarProductsService(
                id -> Mono.just(List.of("4", "2", "3")),
                id -> Mono.just(product(id)), 2);

        StepVerifier.create(service.getSimilarProducts("1"))
                .expectNext(List.of(product("4"), product("2"), product("3")))
                .verifyComplete();
    }

    @Test
    void preservesSimilarityWhenConcurrentLookupsCompleteInReverseOrder() {
        var details = new ControlledDetails("2", "3", "4");
        var service = new GetSimilarProductsService(
                id -> Mono.just(List.of("2", "3", "4")), details, 3);

        StepVerifier.create(service.getSimilarProducts("1"))
                .then(() -> {
                    assertThat(details.requested).containsExactly("2", "3", "4");
                    details.pending.values().forEach(publisher -> publisher.assertSubscribers(1));
                    details.complete("4");
                    details.complete("3");
                    details.complete("2");
                })
                .expectNext(List.of(product("2"), product("3"), product("4")))
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void boundsActiveLookupsAndStartsQueuedWorkAsSlotsBecomeAvailable(int concurrency) {
        var ids = List.of("1", "2", "3", "4", "6");
        var details = new ControlledDetails(ids.toArray(String[]::new));
        var service = new GetSimilarProductsService(id -> Mono.just(ids), details, concurrency);

        StepVerifier.create(service.getSimilarProducts("source"))
                .then(() -> {
                    for (int completed = 0; completed < ids.size(); completed++) {
                        int started = Math.min(ids.size(), completed + concurrency);
                        assertThat(details.requested).containsExactlyElementsOf(ids.subList(0, started));
                        for (int index = completed; index < ids.size(); index++) {
                            details.publisher(ids.get(index)).assertSubscribers(index < started ? 1 : 0);
                        }
                        details.complete(ids.get(completed));
                    }
                })
                .expectNext(ids.stream().map(GetSimilarProductsServiceTests::product).toList())
                .verifyComplete();
    }

    @Test
    void fetchesDuplicateIdsOnceInFirstOccurrenceOrder() {
        var requested = new ArrayList<String>();
        var service = new GetSimilarProductsService(
                id -> Mono.just(List.of("3", "2", "3", "4", "2")),
                id -> {
                    requested.add(id);
                    return Mono.just(product(id));
                }, 2);

        StepVerifier.create(service.getSimilarProducts("1"))
                .expectNext(List.of(product("3"), product("2"), product("4")))
                .verifyComplete();
        assertThat(requested).containsExactly("3", "2", "4");
    }

    @Test
    void failsImmediatelyForAMissingDetailAndCancelsPendingWork() {
        var details = new ControlledDetails("1", "5", "2");
        var service = new GetSimilarProductsService(
                id -> Mono.just(List.of("1", "5", "2")), details, 2);

        StepVerifier.create(service.getSimilarProducts("4"))
                .then(() -> details.publisher("5").complete())
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(MissingProductException.class);
                    assertThat(((MissingProductException) error).productId()).isEqualTo("5");
                })
                .verify();
        details.publisher("1").assertCancelled();
        assertThat(details.requested).containsExactly("1", "5");
        details.publisher("2").assertNoSubscribers();
    }

    @Test
    void propagatesSimilarIdLookupFailureWithoutFetchingDetails() {
        var failure = new IllegalStateException("Similar ID lookup failed");
        var service = new GetSimilarProductsService(id -> Mono.error(failure), noDetails(), 2);

        StepVerifier.create(service.getSimilarProducts("1"))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                .verify();
    }

    @Test
    void failsImmediatelyOnALaterDetailErrorPreservingItsCauseAndCancellingWork() {
        var cause = new IllegalArgumentException("Upstream payload failure");
        var failure = new IllegalStateException("Detail lookup failed", cause);
        var details = new ControlledDetails("1", "6", "2");
        var service = new GetSimilarProductsService(
                id -> Mono.just(List.of("1", "6", "2")), details, 2);

        StepVerifier.create(service.getSimilarProducts("5"))
                .then(() -> details.publisher("6").error(failure))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure).hasCause(cause))
                .verify();
        details.publisher("1").assertCancelled();
        assertThat(details.requested).containsExactly("1", "6");
        details.publisher("2").assertNoSubscribers();
    }

    @Test
    void propagatesCancellationToThePendingSimilarIdLookup() {
        var ids = TestPublisher.<List<String>>create();
        var service = new GetSimilarProductsService(id -> ids.mono(), noDetails(), 2);

        StepVerifier.create(service.getSimilarProducts("1"))
                .then(() -> ids.assertSubscribers(1))
                .thenCancel()
                .verify();
        ids.assertCancelled();
    }

    @Test
    void propagatesCancellationToAllActiveDetailsAndDoesNotStartQueuedLookups() {
        var details = new ControlledDetails("2", "3", "4");
        var service = new GetSimilarProductsService(
                id -> Mono.just(List.of("2", "3", "4")), details, 2);

        StepVerifier.create(service.getSimilarProducts("1"))
                .then(() -> {
                    details.publisher("2").assertSubscribers(1);
                    details.publisher("3").assertSubscribers(1);
                })
                .thenCancel()
                .verify();
        details.publisher("2").assertCancelled();
        details.publisher("3").assertCancelled();
        details.publisher("4").assertNoSubscribers();
        assertThat(details.requested).containsExactly("2", "3");
    }

    @Test
    void defersPortCallsAndKeepsDeduplicationStateIndependentForEachSubscription() {
        var idCalls = new AtomicInteger();
        var detailCalls = new AtomicInteger();
        var service = new GetSimilarProductsService(id -> {
            idCalls.incrementAndGet();
            return Mono.just(List.of("2", "2", "3"));
        }, id -> {
            detailCalls.incrementAndGet();
            return Mono.just(product(id));
        }, 2);

        var result = service.getSimilarProducts("1");
        assertThat(idCalls.get()).isZero();
        assertThat(detailCalls.get()).isZero();
        for (int subscription = 0; subscription < 2; subscription++) {
            StepVerifier.create(result)
                    .expectNext(List.of(product("2"), product("3")))
                    .verifyComplete();
        }
        assertThat(idCalls.get()).isEqualTo(2);
        assertThat(detailCalls.get()).isEqualTo(4);
    }

    @Test
    void rejectsAnIdPortCompletingWithoutAListRatherThanReturningSuccess() {
        var service = new GetSimilarProductsService(id -> Mono.empty(), noDetails(), 2);

        StepVerifier.create(service.getSimilarProducts("1"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Similar-product ID lookup completed without a list"))
                .verify();
    }

    @Test
    void turnsASynchronousIdPortFailureIntoTheOriginalReactiveError() {
        var failure = new IllegalStateException("ID port failed before returning a publisher");
        var service = new GetSimilarProductsService(id -> { throw failure; }, noDetails(), 2);

        StepVerifier.create(service.getSimilarProducts("1"))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                .verify();
    }

    @Test
    void turnsASynchronousDetailPortFailureIntoTheOriginalReactiveError() {
        var failure = new IllegalStateException("Detail port failed before returning a publisher");
        var service = new GetSimilarProductsService(
                id -> Mono.just(List.of("2")), id -> { throw failure; }, 2);

        StepVerifier.create(service.getSimilarProducts("1"))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                .verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonpositiveConcurrency(int concurrency) {
        assertThatIllegalArgumentException().isThrownBy(() -> new GetSimilarProductsService(
                id -> Mono.just(List.of()), noDetails(), concurrency));
    }

    @Test
    void requiresBothOutboundPorts() {
        assertThatNullPointerException().isThrownBy(() ->
                new GetSimilarProductsService(null, noDetails(), 2));
        assertThatNullPointerException().isThrownBy(() ->
                new GetSimilarProductsService(id -> Mono.just(List.of()), null, 2));
    }

    private static Product product(String id) {
        return new Product(id, "Product " + id, new BigDecimal("29.99"), !id.equals("3"));
    }

    private static ProductDetails noDetails() {
        return id -> { throw new AssertionError("Unexpected detail lookup: " + id); };
    }

    private static final class ControlledDetails implements ProductDetails {

        private final Map<String, TestPublisher<Product>> pending = new LinkedHashMap<>();
        private final List<String> requested = new ArrayList<>();

        private ControlledDetails(String... ids) {
            for (String id : ids) {
                pending.put(id, TestPublisher.create());
            }
        }

        @Override
        public Mono<Product> findById(String id) {
            requested.add(id);
            return publisher(id).mono();
        }

        private TestPublisher<Product> publisher(String id) {
            assertThat(pending).containsKey(id);
            return pending.get(id);
        }

        private void complete(String id) {
            publisher(id).emit(product(id));
        }
    }
}
