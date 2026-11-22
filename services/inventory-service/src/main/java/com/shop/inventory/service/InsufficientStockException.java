package com.shop.inventory.service;

public class InsufficientStockException extends RuntimeException {

    private final long productId;

    public InsufficientStockException(long productId, String message) {
        super(message);
        this.productId = productId;
    }

    public long getProductId() {
        return productId;
    }
}
