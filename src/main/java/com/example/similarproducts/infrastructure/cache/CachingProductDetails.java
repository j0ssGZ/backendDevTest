package com.example.similarproducts.infrastructure.cache;

import com.example.similarproducts.application.port.out.ProductDetails;
import com.example.similarproducts.domain.Product;
import com.example.similarproducts.infrastructure.http.UpstreamException;
import com.github.benmanes.caffeine.cache.Cache;
import reactor.core.publisher.Mono;

/** Positive cache only; misses have independent, directly cancellable subscriptions. */
public final class CachingProductDetails implements ProductDetails {

    private final ProductDetails delegate;
    private final Cache<String, Product> cache;

    public CachingProductDetails(ProductDetails delegate, Cache<String, Product> cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public Mono<Product> findById(String productId) {
        return Mono.defer(() -> {
            Product hit = cache.getIfPresent(productId);
            if (hit != null) {
                return Mono.just(hit);
            }
            return Mono.defer(() -> delegate.findById(productId)).doOnNext(product -> {
                if (!productId.equals(product.id())) {
                    throw new UpstreamException(UpstreamException.Kind.INVALID_PAYLOAD, 0,
                            "Cannot cache a mismatching product identity", null);
                }
                cache.put(productId, product);
            });
        });
    }
}
