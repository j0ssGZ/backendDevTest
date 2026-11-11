package com.example.similarproducts.infrastructure.resilience;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import com.example.similarproducts.application.GetSimilarProducts;
import com.example.similarproducts.domain.Product;
import com.example.similarproducts.infrastructure.http.UpstreamException;
import reactor.core.publisher.Mono;

/** One execution budget around the complete use case, not a fresh budget per detail. */
public final class DeadlineGetSimilarProducts implements GetSimilarProducts {

    private final GetSimilarProducts delegate;
    private final Duration timeout;

    public DeadlineGetSimilarProducts(GetSimilarProducts delegate, Duration timeout) {
        this.delegate = delegate;
        this.timeout = timeout;
    }

    @Override
    public Mono<List<Product>> getSimilarProducts(String productId) {
        return Mono.defer(() -> delegate.getSimilarProducts(productId)).timeout(timeout)
                .onErrorMap(TimeoutException.class, cause -> new UpstreamException(
                        UpstreamException.Kind.TIMEOUT, 0, "Overall request execution deadline exceeded", cause));
    }
}
