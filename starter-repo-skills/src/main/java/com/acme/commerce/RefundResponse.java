package com.acme.commerce;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public record RefundResponse(
    String refundId,
    String orderId,
    String merchantId,
    long amountCents,
    String status,
    String orderStatus,
    long totalRefundedCents,
    long refundableCents,
    String paymentChannel,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt
) {}
