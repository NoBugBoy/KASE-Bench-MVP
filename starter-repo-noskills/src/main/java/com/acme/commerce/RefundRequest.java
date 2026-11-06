package com.acme.commerce;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record RefundRequest(
        @NotBlank(message = "merchantId is required")
        String merchantId,
        @Positive(message = "amountCents must be positive")
        Long amountCents,
        String idempotencyKey,
        String reason,
        String reasonDescription,
        String operator
) {
    public RefundRequest(String merchantId, Long amountCents, String idempotencyKey, String reason, String reasonDescription, String operator) {
        this.merchantId = merchantId;
        this.amountCents = amountCents;
        this.idempotencyKey = idempotencyKey;
        this.reason = reason;
        this.reasonDescription = reasonDescription;
        this.operator = operator;
    }
}
