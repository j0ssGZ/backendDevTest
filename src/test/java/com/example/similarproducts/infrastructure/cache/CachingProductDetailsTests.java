package com.example.similarproducts.infrastructure.cache;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.example.similarproducts.application.GetSimilarProductsService;
import com.example.similarproducts.domain.Product;
import com.example.similarproducts.infrastructure.http.UpstreamException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import static org.assertj.core.api.Assertions.assertThat;

class CachingProductDetailsTests {

    private final AtomicLong clock = new AtomicLong();
    private final Cache<String, Product> cache = Caffeine.newBuilder().maximumSize(2)
            .expireAfterWrite(Duration.ofSeconds(30)).ticker(clock::get).executor(Runnable::run).build();

    @Test
    void loadsOnFirstSubscriptionAndReusesAValidValueWithExactPrice() {
        var calls = new AtomicInteger();
        var expected = product("2");
        var details = new CachingProductDetails(id -> { calls.incrementAndGet(); return Mono.just(expected); }, cache);
        var result = details.findById("2");
        assertThat(calls.get()).isZero();

        StepVerifier.create(result).expectNext(expected).verifyComplete();
        StepVerifier.create(result).assertNext(value -> {
            assertThat(value.id()).isEqualTo("2");
            assertThat(value.price().toPlainString()).isEqualTo("1234567890.123456789012345678900");
            assertThat(value.availability()).isFalse();
        }).verifyComplete();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void expiresAfterWriteEvenWhenAnEntryWasReadRecently() {
        var calls = new AtomicInteger();
        var details = new CachingProductDetails(id -> { calls.incrementAndGet(); return Mono.just(product(id)); }, cache);
        StepVerifier.create(details.findById("2")).expectNext(product("2")).verifyComplete();
        clock.set(Duration.ofSeconds(29).toNanos());
        StepVerifier.create(details.findById("2")).expectNext(product("2")).verifyComplete();
        assertThat(calls.get()).isEqualTo(1);
        clock.set(Duration.ofSeconds(30).toNanos());
        StepVerifier.create(details.findById("2")).expectNext(product("2")).verifyComplete();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void evictsAtCapacityAndReloadsAnEvictedEntry() {
        var calls = new AtomicInteger();
        var details = new CachingProductDetails(id -> { calls.incrementAndGet(); return Mono.just(product(id)); }, cache);
        for (String id : List.of("1", "2", "3")) {
            StepVerifier.create(details.findById(id)).expectNext(product(id)).verifyComplete();
        }
        cache.cleanUp();
        assertThat(cache.estimatedSize()).isLessThanOrEqualTo(2);
        String evicted = List.of("1", "2", "3").stream().filter(id -> cache.getIfPresent(id) == null).findFirst().orElseThrow();
        StepVerifier.create(details.findById(evicted)).expectNext(product(evicted)).verifyComplete();
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void doesNotCacheFailedLoadsAndPreservesTheOriginalFailure() {
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("upstream failure");
        var details = new CachingProductDetails(id -> calls.incrementAndGet() == 1
                ? Mono.error(failure) : Mono.just(product(id)), cache);
        StepVerifier.create(details.findById("2")).expectErrorSatisfies(error -> assertThat(error).isSameAs(failure)).verify();
        assertThat(cache.getIfPresent("2")).isNull();
        StepVerifier.create(details.findById("2")).expectNext(product("2")).verifyComplete();
        StepVerifier.create(details.findById("2")).expectNext(product("2")).verifyComplete();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void doesNotCacheMissingProducts() {
        var calls = new AtomicInteger();
        var details = new CachingProductDetails(id -> calls.incrementAndGet() == 1
                ? Mono.empty() : Mono.just(product(id)), cache);
        StepVerifier.create(details.findById("2")).verifyComplete();
        assertThat(cache.getIfPresent("2")).isNull();
        StepVerifier.create(details.findById("2")).expectNext(product("2")).verifyComplete();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void rejectsMismatchingIdentitiesWithoutPoisoningTheCache() {
        var details = new CachingProductDetails(id -> Mono.just(product("wrong")), cache);
        StepVerifier.create(details.findById("2")).expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(UpstreamException.class);
            assertThat(((UpstreamException) error).kind()).isEqualTo(UpstreamException.Kind.INVALID_PAYLOAD);
        }).verify();
        assertThat(cache.estimatedSize()).isZero();
    }

    @Test
    void concurrentMissesRemainIndependentAndSubsequentAccessHits() {
        var first = TestPublisher.<Product>create();
        var second = TestPublisher.<Product>create();
        var calls = new AtomicInteger();
        var details = new CachingProductDetails(id -> calls.incrementAndGet() == 1 ? first.mono() : second.mono(), cache);

        StepVerifier.create(Flux.merge(details.findById("2"), details.findById("2")))
                .then(() -> {
                    first.assertSubscribers(1);
                    second.assertSubscribers(1);
                    second.emit(product("2"));
                    first.emit(product("2"));
                }).expectNext(product("2"), product("2")).verifyComplete();
        StepVerifier.create(details.findById("2")).expectNext(product("2")).verifyComplete();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void cancellationDoesNotFillTheCacheOrCancelAnotherCallersLoad() {
        var first = TestPublisher.<Product>create();
        var second = TestPublisher.<Product>create();
        var calls = new AtomicInteger();
        var details = new CachingProductDetails(id -> calls.incrementAndGet() == 1 ? first.mono() : second.mono(), cache);

        StepVerifier.create(details.findById("2"))
                .then(() -> {
                    StepVerifier.create(details.findById("2")).thenCancel().verify();
                    second.assertCancelled();
                    first.assertSubscribers(1);
                    assertThat(cache.getIfPresent("2")).isNull();
                    first.emit(product("2"));
                }).expectNext(product("2")).verifyComplete();
        assertThat(cache.getIfPresent("2")).isEqualTo(product("2"));
    }

    @Test
    void cancelsAnAbandonedLoadWithoutLeavingACacheEntry() {
        var pending = TestPublisher.<Product>create();
        var details = new CachingProductDetails(id -> pending.mono(), cache);
        StepVerifier.create(details.findById("2")).thenCancel().verify();
        pending.assertCancelled();
        assertThat(cache.estimatedSize()).isZero();
    }

    @Test
    void cacheHitsPreserveSimilarityOrderAndNeverCacheIdLists() {
        cache.put("2", product("2"));
        cache.put("1", product("1"));
        var idsCalls = new AtomicInteger();
        var details = new CachingProductDetails(id -> { throw new AssertionError("Unexpected load"); }, cache);
        var service = new GetSimilarProductsService(id -> {
            idsCalls.incrementAndGet(); return Mono.just(List.of("2", "1"));
        }, details, 2);
        for (int i = 0; i < 2; i++) {
            StepVerifier.create(service.getSimilarProducts("source"))
                    .expectNext(List.of(product("2"), product("1"))).verifyComplete();
        }
        assertThat(idsCalls.get()).isEqualTo(2);
    }

    private static Product product(String id) {
        return new Product(id, "Product", new BigDecimal("1234567890.123456789012345678900"), false);
    }
}
