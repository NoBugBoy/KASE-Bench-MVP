package com.acme.commerce;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final LegacyRefundService legacyRefundService;
    private final RefundService refundService;

    @Autowired
    public OrderController(LegacyRefundService legacyRefundService, RefundService refundService) {
        this.legacyRefundService = legacyRefundService;
        this.refundService = refundService;
    }

    public OrderController(LegacyRefundService legacyRefundService) {
        this(legacyRefundService, null);
    }

    // Legacy endpoint contract: preserve path, request fields and response fields.
    @PostMapping("/{orderId}/refund")
    public LegacyRefundResponse refundAll(@PathVariable String orderId, @RequestBody LegacyRefundRequest request) {
        if (refundService != null) {
            RefundResponse response = refundService.refund(orderId, new RefundRequest(
                    request.merchantId(),
                    request.amountCents(),
                    request.idempotencyKey(),
                    request.reason() != null ? request.reason() : "LEGACY_REFUND",
                    request.reasonDescription(),
                    request.operator()
            ));
            return new LegacyRefundResponse(orderId, response.orderStatus().name(), response.amount());
        }
        double amount = legacyRefundService.refundAll(orderId, request.merchantId(), request.operator());
        return new LegacyRefundResponse(orderId, "REFUNDED", amount);
    }

    // Modern RESTful endpoint supporting partial refunds and full metadata
    @PostMapping("/{orderId}/refunds")
    public ResponseEntity<RefundResponse> createRefund(
            @PathVariable String orderId,
            @Valid @RequestBody RefundRequest request) {
        RefundResponse response = refundService.refund(orderId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{orderId}/refunds")
    public ResponseEntity<List<RefundResponse>> getRefunds(@PathVariable String orderId) {
        return ResponseEntity.ok(refundService.getRefundsForOrder(orderId));
    }

    public record LegacyRefundRequest(
            String merchantId,
            String operator,
            Long amountCents,
            String idempotencyKey,
            String reason,
            String reasonDescription
    ) {
        public LegacyRefundRequest(String merchantId, String operator) {
            this(merchantId, operator, null, null, null, null);
        }
    }

    public record LegacyRefundResponse(String orderId, String status, double refundedAmount) {}
}
