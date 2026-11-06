package com.acme.commerce;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LegacyRefundService {
    private final RefundService refundService;

    @Autowired
    public LegacyRefundService(RefundService refundService) {
        this.refundService = refundService;
    }

    public LegacyRefundService(OrderRepository orderRepository, PaymentGateway paymentGateway) {
        this(new RefundService(orderRepository, new RefundRepository(), paymentGateway));
    }

    // Existing behavior: kept as a compatibility surface. Do not remove this method.
    public double refundAll(String orderId, String merchantId, String operator) {
        RefundResponse response = refundService.refund(orderId, new RefundRequest(
                merchantId,
                null,
                null,
                "LEGACY_FULL_REFUND",
                "Full refund via LegacyRefundService",
                operator
        ));
        return response.amount();
    }
}
