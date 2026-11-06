package com.acme.commerce;

public class InvalidRefundAmountException extends IllegalArgumentException {
    public InvalidRefundAmountException(String message) {
        super(message);
    }
}
