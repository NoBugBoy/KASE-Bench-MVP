package com.acme.commerce;

import java.time.Instant;

public record RefundResponse(
        String refundId,
        String orderId,
        String merchantId,
        long amountCents,
        double amount,
        RefundStatus status,
        OrderStatus orderStatus,
        long totalRefundedAmountCents,
        long remainingAmountCents,
        String failureCode,
        Instant createdAt
) {}
