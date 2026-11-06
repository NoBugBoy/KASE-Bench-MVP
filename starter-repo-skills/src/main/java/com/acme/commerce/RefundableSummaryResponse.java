package com.acme.commerce;

public record RefundableSummaryResponse(
    String orderId,
    long paymentAmountCents,
    long totalRefundedCents,
    long refundableCents,
    String orderStatus,
    String paymentChannel
) {}
