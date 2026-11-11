package com.example.similarproducts.application;

import java.util.List;

import com.example.similarproducts.domain.Product;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface GetSimilarProducts {

    /**
     * Emits one list ordered by similarity, or an error. An empty list is a valid result.
     * Each subscription performs its own lookups; cancellation cancels pending work.
     */
    Mono<List<Product>> getSimilarProducts(String productId);
}
