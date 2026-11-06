package com.acme.commerce;

public class PaymentGatewayException extends IllegalStateException {
    private final String failureCode;

    public PaymentGatewayException(String failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    public PaymentGatewayException(String message) {
        this("GATEWAY_ERROR", message);
    }

    public String getFailureCode() {
        return failureCode;
    }
}
