package com.acme.commerce;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final LegacyRefundService legacyRefundService;
    private final RefundApplicationService refundApplicationService;

    @Autowired
    public OrderController(LegacyRefundService legacyRefundService, RefundApplicationService refundApplicationService) {
        this.legacyRefundService = legacyRefundService;
        this.refundApplicationService = refundApplicationService;
    }

    public OrderController(LegacyRefundService legacyRefundService) {
        this(legacyRefundService, null);
    }

    @PostMapping("/{orderId}/refunds")
    public RefundResponse createRefund(
            @PathVariable String orderId,
            @RequestBody RefundCreateRequest request) {
        return refundApplicationService.refund(orderId, request);
    }

    @GetMapping("/{orderId}/refundable")
    public RefundableSummaryResponse getRefundableSummary(
            @PathVariable String orderId,
            @RequestParam String merchantId) {
        return refundApplicationService.getRefundableSummary(orderId, merchantId);
    }

    // Legacy endpoint contract: preserve path, request fields and response fields.
    @PostMapping("/{orderId}/refund")
    public LegacyRefundResponse refundAll(
            @PathVariable String orderId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestBody LegacyRefundRequest request) {
        String merchantId = request != null ? request.merchantId() : null;
        String operator = request != null ? request.operator() : null;
        double amount = legacyRefundService.refundAll(orderId, merchantId, operator, requestId);
        return new LegacyRefundResponse(orderId, "REFUNDED", amount);
    }

    public record LegacyRefundRequest(String merchantId, String operator) {}
    public record LegacyRefundResponse(String orderId, String status, double refundedAmount) {}
}
