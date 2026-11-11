package com.example.similarproducts.application.port.out;

import java.util.List;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface SimilarProductIds {

    /**
     * Emits one ordered list of non-null string IDs, or an error.
     * Emit an empty list for no similarities; completion without a list violates this contract.
     */
    Mono<List<String>> findSimilarIds(String productId);
}
