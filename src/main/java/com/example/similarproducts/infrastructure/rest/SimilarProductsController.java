package com.example.similarproducts.infrastructure.rest;

import java.util.List;

import com.example.similarproducts.application.GetSimilarProducts;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public final class SimilarProductsController {

    private final GetSimilarProducts useCase;

    public SimilarProductsController(GetSimilarProducts useCase) {
        this.useCase = useCase;
    }

    @GetMapping(value = "/product/{productId}/similar", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<ProductResponse>> similar(@PathVariable String productId) {
        return useCase.getSimilarProducts(productId)
                .map(products -> products.stream().map(ProductResponse::from).toList());
    }
}
