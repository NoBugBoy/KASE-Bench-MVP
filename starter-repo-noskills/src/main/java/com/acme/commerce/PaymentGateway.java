package com.acme.commerce;

public interface PaymentGateway {
    GatewayResult refund(String paymentChannel, String paymentTransactionId, String refundId, long amountCents);
    record GatewayResult(boolean success, String failureCode) {}
}
