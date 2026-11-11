package com.example.similarproducts.application.port.out;

import com.example.similarproducts.domain.Product;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface ProductDetails {

    /**
     * Emits the requested product, completes empty if absent, or emits a failure.
     * Implementations must support cancellation of pending retrieval.
     */
    Mono<Product> findById(String productId);
}
