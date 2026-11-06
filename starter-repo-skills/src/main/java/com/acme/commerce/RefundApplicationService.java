package com.acme.commerce;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class RefundApplicationService {
    private static final DateTimeFormatter ID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("UTC"));

    private final OrderRepository orderRepository;
    private final RefundRepository refundRepository;
    private final PaymentGateway paymentGateway;
    private final ConcurrentHashMap<String, ReentrantLock> orderLocks = new ConcurrentHashMap<>();
    private final AtomicLong refundSeq = new AtomicLong(1);

    public RefundApplicationService(OrderRepository orderRepository,
                                    RefundRepository refundRepository,
                                    PaymentGateway paymentGateway) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.paymentGateway = paymentGateway;
    }

    public RefundResponse refund(String orderId, RefundCreateRequest request) {
        if (orderId == null || orderId.isBlank()) {
            throw new RefundException("INVALID_REFUND_REQUEST", "orderId is required", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        validateRequest(request);

        String fingerprint = computeFingerprint(
                request.merchantId(), orderId, request.amountCents(), request.reason(), request.reasonDescription()
        );

        // Step 2 & 3: Check idempotency outside lock
        Optional<Refund> existingOpt = refundRepository.findByIdempotency(request.merchantId(), orderId, request.idempotencyKey());
        if (existingOpt.isPresent()) {
            Refund existing = existingOpt.get();
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw new RefundException("IDEMPOTENCY_CONFLICT", "Idempotency conflict for key " + request.idempotencyKey(), HttpStatus.CONFLICT);
            }
            Order order = orderRepository.findById(orderId).orElseThrow(() ->
                    new RefundException("ORDER_NOT_FOUND", "Order not found: " + orderId, HttpStatus.NOT_FOUND));
            return buildResponse(existing, order);
        }

        // Step 4: Acquire order-level lock
        ReentrantLock lock = orderLocks.computeIfAbsent(orderId, k -> new ReentrantLock());
        boolean acquired;
        try {
            acquired = lock.tryLock(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RefundException("REFUND_IN_PROGRESS", "Refund lock interrupted", HttpStatus.CONFLICT);
        }
        if (!acquired) {
            throw new RefundException("REFUND_IN_PROGRESS", "Refund in progress for order " + orderId, HttpStatus.CONFLICT);
        }

        try {
            // Step 5: Double check idempotency in lock
            existingOpt = refundRepository.findByIdempotency(request.merchantId(), orderId, request.idempotencyKey());
            if (existingOpt.isPresent()) {
                Refund existing = existingOpt.get();
                if (!existing.getRequestFingerprint().equals(fingerprint)) {
                    throw new RefundException("IDEMPOTENCY_CONFLICT", "Idempotency conflict for key " + request.idempotencyKey(), HttpStatus.CONFLICT);
                }
                Order order = orderRepository.findById(orderId).orElseThrow(() ->
                        new RefundException("ORDER_NOT_FOUND", "Order not found: " + orderId, HttpStatus.NOT_FOUND));
                return buildResponse(existing, order);
            }

            if (refundRepository.hasProcessingRefund(orderId)) {
                throw new RefundException("REFUND_IN_PROGRESS", "Refund in progress for order " + orderId, HttpStatus.CONFLICT);
            }

            // Step 6: Find order & validate merchantId
            Order order = orderRepository.findById(orderId).orElseThrow(() ->
                    new RefundException("ORDER_NOT_FOUND", "Order not found: " + orderId, HttpStatus.NOT_FOUND));
            if (!order.getMerchantId().equals(request.merchantId())) {
                throw new RefundException("MERCHANT_MISMATCH", "Merchant mismatch for order: " + orderId, HttpStatus.FORBIDDEN);
            }

            // Step 7: Validate order status
            if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.PARTIALLY_REFUNDED) {
                throw new RefundException("ORDER_NOT_REFUNDABLE", "Order status " + order.getStatus() + " is not refundable", HttpStatus.CONFLICT);
            }

            // Step 8 & 9: Calculate refundable amount
            long totalRefunded = refundRepository.sumSuccessfulByOrder(orderId);
            long refundable = order.getPaymentAmountCents() - totalRefunded;

            // Step 10: Validate amount
            if (request.amountCents() > refundable) {
                throw new RefundException("REFUND_AMOUNT_EXCEEDED", "Refund amount exceeds refundable amount", HttpStatus.CONFLICT);
            }

            // Step 11: Create PROCESSING refund
            String refundId = generateRefundId();
            Refund refund = new Refund(
                    refundId,
                    orderId,
                    request.merchantId(),
                    request.idempotencyKey(),
                    fingerprint,
                    request.amountCents(),
                    request.reason(),
                    request.reasonDescription(),
                    order.getPaymentChannel(),
                    order.getPaymentTransactionId(),
                    RefundStatus.PROCESSING
            );
            refundRepository.save(refund);

            // Step 12: Call payment gateway with original payment info
            PaymentGateway.GatewayResult result;
            try {
                result = paymentGateway.refund(
                        order.getPaymentChannel(),
                        order.getPaymentTransactionId(),
                        refund.getRefundId(),
                        request.amountCents()
                );
            } catch (Exception e) {
                refund.markFailed("GATEWAY_ERROR");
                refundRepository.save(refund);
                throw new RefundException("PAYMENT_CHANNEL_FAILED", "Payment channel error: " + e.getMessage(), HttpStatus.BAD_GATEWAY);
            }

            // Step 13: Success
            if (result.success()) {
                refund.markSuccess();
                refundRepository.save(refund);

                long newTotalRefunded = refundRepository.sumSuccessfulByOrder(orderId);
                if (newTotalRefunded >= order.getPaymentAmountCents()) {
                    order.setStatus(OrderStatus.REFUNDED);
                } else if (newTotalRefunded > 0) {
                    order.setStatus(OrderStatus.PARTIALLY_REFUNDED);
                }
                orderRepository.save(order);

                return buildResponse(refund, order);
            } else {
                // Step 14: Failed
                String code = (result.failureCode() != null && !result.failureCode().isBlank())
                        ? result.failureCode() : "CHANNEL_DECLINED";
                refund.markFailed(code);
                refundRepository.save(refund);
                throw new RefundException("PAYMENT_CHANNEL_FAILED", "Payment channel declined refund: " + code, HttpStatus.BAD_GATEWAY);
            }
        } finally {
            // Step 15: Release lock
            lock.unlock();
        }
    }

    public double refundRemaining(String orderId, String merchantId, String operator, String idempotencyKey) {
        if (orderId == null || orderId.isBlank()) {
            throw new RefundException("INVALID_REFUND_REQUEST", "orderId is required", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (merchantId == null || merchantId.isBlank()) {
            throw new RefundException("INVALID_REFUND_REQUEST", "merchantId is required", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        ReentrantLock lock = orderLocks.computeIfAbsent(orderId, k -> new ReentrantLock());
        boolean acquired;
        try {
            acquired = lock.tryLock(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RefundException("REFUND_IN_PROGRESS", "Refund lock interrupted", HttpStatus.CONFLICT);
        }
        if (!acquired) {
            throw new RefundException("REFUND_IN_PROGRESS", "Refund in progress for order " + orderId, HttpStatus.CONFLICT);
        }

        try {
            if (refundRepository.hasProcessingRefund(orderId)) {
                throw new RefundException("REFUND_IN_PROGRESS", "Refund in progress for order " + orderId, HttpStatus.CONFLICT);
            }

            Order order = orderRepository.findById(orderId).orElseThrow(() ->
                    new RefundException("ORDER_NOT_FOUND", "Order not found: " + orderId, HttpStatus.NOT_FOUND));
            if (!order.getMerchantId().equals(merchantId)) {
                throw new RefundException("MERCHANT_MISMATCH", "Merchant mismatch for order: " + orderId, HttpStatus.FORBIDDEN);
            }
            if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.PARTIALLY_REFUNDED) {
                throw new RefundException("ORDER_NOT_REFUNDABLE", "Order status " + order.getStatus() + " is not refundable", HttpStatus.CONFLICT);
            }

            long totalRefunded = refundRepository.sumSuccessfulByOrder(orderId);
            long refundable = order.getPaymentAmountCents() - totalRefunded;
            if (refundable <= 0) {
                throw new RefundException("ORDER_NOT_REFUNDABLE", "Order has no refundable amount remaining", HttpStatus.CONFLICT);
            }

            String actualOperator = (operator != null && !operator.isBlank()) ? operator : "system";
            String key = (idempotencyKey != null && !idempotencyKey.isBlank()) ? idempotencyKey : ("legacy-" + UUID.randomUUID());
            String reason = "CUSTOMER_REQUEST";
            String description = "Legacy full refund by " + actualOperator;

            String fingerprint = computeFingerprint(merchantId, orderId, refundable, reason, description);

            Optional<Refund> existingOpt = refundRepository.findByIdempotency(merchantId, orderId, key);
            if (existingOpt.isPresent()) {
                Refund existing = existingOpt.get();
                if (!existing.getRequestFingerprint().equals(fingerprint)) {
                    throw new RefundException("IDEMPOTENCY_CONFLICT", "Idempotency conflict for key " + key, HttpStatus.CONFLICT);
                }
                return existing.getAmountCents() / 100.0;
            }

            String refundId = generateRefundId();
            Refund refund = new Refund(
                    refundId,
                    orderId,
                    merchantId,
                    key,
                    fingerprint,
                    refundable,
                    reason,
                    description,
                    order.getPaymentChannel(),
                    order.getPaymentTransactionId(),
                    RefundStatus.PROCESSING
            );
            refundRepository.save(refund);

            PaymentGateway.GatewayResult result;
            try {
                result = paymentGateway.refund(
                        order.getPaymentChannel(),
                        order.getPaymentTransactionId(),
                        refund.getRefundId(),
                        refundable
                );
            } catch (Exception e) {
                refund.markFailed("GATEWAY_ERROR");
                refundRepository.save(refund);
                throw new RefundException("PAYMENT_CHANNEL_FAILED", "Payment channel error: " + e.getMessage(), HttpStatus.BAD_GATEWAY);
            }

            if (result.success()) {
                refund.markSuccess();
                refundRepository.save(refund);

                long newTotalRefunded = refundRepository.sumSuccessfulByOrder(orderId);
                if (newTotalRefunded >= order.getPaymentAmountCents()) {
                    order.setStatus(OrderStatus.REFUNDED);
                } else if (newTotalRefunded > 0) {
                    order.setStatus(OrderStatus.PARTIALLY_REFUNDED);
                }
                orderRepository.save(order);

                return refundable / 100.0;
            } else {
                String code = (result.failureCode() != null && !result.failureCode().isBlank())
                        ? result.failureCode() : "CHANNEL_DECLINED";
                refund.markFailed(code);
                refundRepository.save(refund);
                throw new RefundException("PAYMENT_CHANNEL_FAILED", "Payment channel declined refund: " + code, HttpStatus.BAD_GATEWAY);
            }
        } finally {
            lock.unlock();
        }
    }

    public RefundableSummaryResponse getRefundableSummary(String orderId, String merchantId) {
        if (orderId == null || orderId.isBlank()) {
            throw new RefundException("INVALID_REFUND_REQUEST", "orderId is required", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (merchantId == null || merchantId.isBlank()) {
            throw new RefundException("INVALID_REFUND_REQUEST", "merchantId is required", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Order order = orderRepository.findById(orderId).orElseThrow(() ->
                new RefundException("ORDER_NOT_FOUND", "Order not found: " + orderId, HttpStatus.NOT_FOUND));
        if (!order.getMerchantId().equals(merchantId)) {
            throw new RefundException("MERCHANT_MISMATCH", "Merchant mismatch for order: " + orderId, HttpStatus.FORBIDDEN);
        }
        long totalRefunded = refundRepository.sumSuccessfulByOrder(orderId);
        long refundable = Math.max(0, order.getPaymentAmountCents() - totalRefunded);
        return new RefundableSummaryResponse(
                order.getOrderId(),
                order.getPaymentAmountCents(),
                totalRefunded,
                refundable,
                order.getStatus().name(),
                order.getPaymentChannel()
        );
    }

    private void validateRequest(RefundCreateRequest request) {
        if (request == null) {
            throw new RefundException("INVALID_REFUND_REQUEST", "Request body is required", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (request.merchantId() == null || request.merchantId().isBlank() || request.merchantId().length() > 32) {
            throw new RefundException("INVALID_REFUND_REQUEST", "merchantId must be between 1 and 32 characters", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (request.amountCents() == null || request.amountCents() <= 0) {
            throw new RefundException("INVALID_REFUND_REQUEST", "amountCents must be greater than 0", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new RefundException("INVALID_REFUND_REQUEST", "reason is required", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        RefundReason reasonEnum;
        try {
            reasonEnum = RefundReason.valueOf(request.reason());
        } catch (IllegalArgumentException e) {
            throw new RefundException("INVALID_REFUND_REQUEST", "Invalid refund reason: " + request.reason(), HttpStatus.UNPROCESSABLE_ENTITY);
        }

        if (reasonEnum == RefundReason.OTHER) {
            if (request.reasonDescription() == null || request.reasonDescription().isBlank() || request.reasonDescription().length() > 200) {
                throw new RefundException("INVALID_REFUND_REQUEST", "reasonDescription is required for reason OTHER (1-200 characters)", HttpStatus.UNPROCESSABLE_ENTITY);
            }
        } else {
            if (request.reasonDescription() != null && request.reasonDescription().length() > 200) {
                throw new RefundException("INVALID_REFUND_REQUEST", "reasonDescription must not exceed 200 characters", HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }

        if (request.idempotencyKey() == null || request.idempotencyKey().length() < 8 || request.idempotencyKey().length() > 64) {
            throw new RefundException("INVALID_REFUND_REQUEST", "idempotencyKey length must be between 8 and 64 characters", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private String computeFingerprint(String merchantId, String orderId, long amountCents, String reason, String reasonDescription) {
        String raw = merchantId + "|" + orderId + "|" + amountCents + "|" + reason + "|" + (reasonDescription != null ? reasonDescription.trim() : "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return raw;
        }
    }

    private String generateRefundId() {
        return "RF" + ID_FORMATTER.format(Instant.now()) + String.format("%04d", refundSeq.getAndIncrement() % 10000);
    }

    private RefundResponse buildResponse(Refund refund, Order order) {
        long totalRefunded = refundRepository.sumSuccessfulByOrder(order.getOrderId());
        long refundable = Math.max(0, order.getPaymentAmountCents() - totalRefunded);
        return new RefundResponse(
                refund.getRefundId(),
                refund.getOrderId(),
                refund.getMerchantId(),
                refund.getAmountCents(),
                refund.getStatus().name(),
                order.getStatus().name(),
                totalRefunded,
                refundable,
                refund.getPaymentChannel(),
                refund.getCreatedAt()
        );
    }
}
