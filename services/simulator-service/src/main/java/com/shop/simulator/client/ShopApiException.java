package com.shop.simulator.client;

/** Lỗi trả về từ hệ thống (qua API Gateway). status = 0 nếu không kết nối được. */
public class ShopApiException extends RuntimeException {

    private final int status;

    public ShopApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
