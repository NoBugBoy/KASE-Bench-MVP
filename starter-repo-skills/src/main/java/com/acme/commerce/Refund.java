package com.acme.commerce;

import java.time.Instant;

public class Refund {
    private final String refundId;
    private final String orderId;
    private final String merchantId;
    private final String idempotencyKey;
    private final String requestFingerprint;
    private final long amountCents;
    private final String reason;
    private final String reasonDescription;
    private final String paymentChannel;
    private final String paymentTransactionId;
    private RefundStatus status;
    private String failureCode;
    private final Instant createdAt;
    private Instant updatedAt;

    public Refund(String refundId, String orderId, String merchantId, String idempotencyKey,
                  String requestFingerprint, long amountCents, String reason, String reasonDescription,
                  String paymentChannel, String paymentTransactionId, RefundStatus status) {
        this.refundId = refundId; this.orderId = orderId; this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey; this.requestFingerprint = requestFingerprint;
        this.amountCents = amountCents; this.reason = reason; this.reasonDescription = reasonDescription;
        this.paymentChannel = paymentChannel; this.paymentTransactionId = paymentTransactionId;
        this.status = status; this.createdAt = Instant.now(); this.updatedAt = this.createdAt;
    }
    public String getRefundId() { return refundId; }
    public String getOrderId() { return orderId; }
    public String getMerchantId() { return merchantId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public long getAmountCents() { return amountCents; }
    public String getReason() { return reason; }
    public String getReasonDescription() { return reasonDescription; }
    public String getPaymentChannel() { return paymentChannel; }
    public String getPaymentTransactionId() { return paymentTransactionId; }
    public RefundStatus getStatus() { return status; }
    public String getFailureCode() { return failureCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void markSuccess() { this.status = RefundStatus.SUCCESS; this.updatedAt = Instant.now(); }
    public void markFailed(String code) { this.status = RefundStatus.FAILED; this.failureCode = code; this.updatedAt = Instant.now(); }
}
