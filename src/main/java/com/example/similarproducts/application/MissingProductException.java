package com.example.similarproducts.application;

import java.util.Objects;

/** A requested similar product has no detail; this does not imply the source is missing. */
public final class MissingProductException extends RuntimeException {

    private final String productId;

    public MissingProductException(String productId) {
        super("Product details missing for ID: " + productId);
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
    }

    public String productId() {
        return productId;
    }
}
