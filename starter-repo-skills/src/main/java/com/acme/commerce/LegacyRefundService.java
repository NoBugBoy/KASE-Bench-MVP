package com.acme.commerce;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LegacyRefundService {
    private final RefundApplicationService refundApplicationService;

    @Autowired
    public LegacyRefundService(OrderRepository orderRepository, PaymentGateway paymentGateway,
                               RefundApplicationService refundApplicationService) {
        this.refundApplicationService = refundApplicationService;
    }

    public LegacyRefundService(OrderRepository orderRepository, PaymentGateway paymentGateway) {
        this(orderRepository, paymentGateway,
                new RefundApplicationService(orderRepository, new RefundRepository(), paymentGateway));
    }

    // Existing behavior: kept as a compatibility surface. Do not remove this method.
    public double refundAll(String orderId, String merchantId, String operator) {
        return refundAll(orderId, merchantId, operator, null);
    }

    public double refundAll(String orderId, String merchantId, String operator, String idempotencyKey) {
        return refundApplicationService.refundRemaining(orderId, merchantId, operator, idempotencyKey);
    }
}
