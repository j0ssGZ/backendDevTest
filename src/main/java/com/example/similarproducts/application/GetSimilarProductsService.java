package com.example.similarproducts.application;

import java.util.List;
import java.util.Objects;

import com.example.similarproducts.application.port.out.ProductDetails;
import com.example.similarproducts.application.port.out.SimilarProductIds;
import com.example.similarproducts.domain.Product;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class GetSimilarProductsService implements GetSimilarProducts {

    private final SimilarProductIds similarProductIds;
    private final ProductDetails productDetails;
    private final int maxConcurrency;

    public GetSimilarProductsService(
            SimilarProductIds similarProductIds, ProductDetails productDetails, int maxConcurrency) {
        this.similarProductIds = Objects.requireNonNull(similarProductIds, "similarProductIds must not be null");
        this.productDetails = Objects.requireNonNull(productDetails, "productDetails must not be null");
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be positive");
        }
        this.maxConcurrency = maxConcurrency;
    }

    @Override
    public Mono<List<Product>> getSimilarProducts(String productId) {
        return Mono.defer(() -> similarProductIds.findSimilarIds(productId))
                .switchIfEmpty(Mono.error(() ->
                        new IllegalStateException("Similar-product ID lookup completed without a list")))
                .flatMapMany(Flux::fromIterable)
                .distinct()
                .flatMapSequential(id -> Mono.defer(() -> productDetails.findById(id))
                                .switchIfEmpty(Mono.error(() -> new MissingProductException(id))),
                        maxConcurrency, 1)
                .collectList();
    }
}
