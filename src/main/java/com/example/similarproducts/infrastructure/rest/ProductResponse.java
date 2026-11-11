package com.example.similarproducts.infrastructure.rest;

import java.math.BigDecimal;

import com.example.similarproducts.domain.Product;

public record ProductResponse(String id, String name, BigDecimal price, boolean availability) {

    static ProductResponse from(Product product) {
        return new ProductResponse(product.id(), product.name(), product.price(), product.availability());
    }
}
