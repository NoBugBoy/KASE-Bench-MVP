package com.acme.commerce;

public class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
