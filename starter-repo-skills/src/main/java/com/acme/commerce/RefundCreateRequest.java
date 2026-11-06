package com.acme.commerce;

public record RefundCreateRequest(
    String merchantId,
    Long amountCents,
    String reason,
    String reasonDescription,
    String idempotencyKey
) {}
