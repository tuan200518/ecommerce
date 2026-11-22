package com.shop.support.kafka;

/** Sự kiện sai định dạng: không retry, chuyển thẳng vào Dead Letter Topic. */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message) {
        super(message);
    }

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
