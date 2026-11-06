package com.acme.commerce;

public class Order {
    private final String orderId;
    private final String merchantId;
    private final long paymentAmountCents;
    private final String paymentChannel;
    private final String paymentTransactionId;
    private OrderStatus status;

    public Order(String orderId, String merchantId, long paymentAmountCents,
                 String paymentChannel, String paymentTransactionId, OrderStatus status) {
        this.orderId = orderId;
        this.merchantId = merchantId;
        this.paymentAmountCents = paymentAmountCents;
        this.paymentChannel = paymentChannel;
        this.paymentTransactionId = paymentTransactionId;
        this.status = status;
    }
    public String getOrderId() { return orderId; }
    public String getMerchantId() { return merchantId; }
    public long getPaymentAmountCents() { return paymentAmountCents; }
    public String getPaymentChannel() { return paymentChannel; }
    public String getPaymentTransactionId() { return paymentTransactionId; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
}
