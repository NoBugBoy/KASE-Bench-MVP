package com.acme.commerce;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RefundService {
    private final OrderRepository orderRepository;
    private final RefundRepository refundRepository;
    private final PaymentGateway paymentGateway;
    private final ConcurrentHashMap<String, Object> orderLocks = new ConcurrentHashMap<>();

    public RefundService(OrderRepository orderRepository,
                         RefundRepository refundRepository,
                         PaymentGateway paymentGateway) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.paymentGateway = paymentGateway;
    }

    public RefundResponse refund(String orderId, RefundRequest request) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId cannot be empty");
        }
        if (request == null || request.merchantId() == null || request.merchantId().isBlank()) {
            throw new IllegalArgumentException("merchantId is required");
        }

        Object lock = orderLocks.computeIfAbsent(orderId, k -> new Object());
        synchronized (lock) {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));

            if (!Objects.equals(order.getMerchantId(), request.merchantId())) {
                throw new IllegalArgumentException("merchant mismatch");
            }

            String idempotencyKey = request.idempotencyKey();
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                Optional<Refund> existingOpt = refundRepository.findByIdempotency(request.merchantId(), orderId, idempotencyKey);
                if (existingOpt.isPresent()) {
                    Refund existing = existingOpt.get();
                    String expectedFingerprint = calculateFingerprint(request.merchantId(), orderId, request.amountCents());
                    if (!Objects.equals(existing.getRequestFingerprint(), expectedFingerprint)) {
                        throw new IdempotencyConflictException("Idempotency key conflict: request parameters do not match previous attempt");
                    }

                    if (existing.getStatus() == RefundStatus.SUCCESS) {
                        long totalRefunded = refundRepository.sumSuccessfulByOrder(orderId);
                        long remaining = Math.max(0, order.getPaymentAmountCents() - totalRefunded);
                        return toResponse(existing, order.getStatus(), totalRefunded, remaining);
                    } else if (existing.getStatus() == RefundStatus.PROCESSING) {
                        throw new IdempotencyConflictException("Refund request is currently processing");
                    } else if (existing.getStatus() == RefundStatus.FAILED) {
                        return retryFailedRefund(order, existing);
                    }
                }
            }

            if (order.getStatus() == OrderStatus.REFUNDED) {
                throw new IllegalStateException("Order is already fully refunded");
            }
            if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.PARTIALLY_REFUNDED) {
                throw new IllegalStateException("Order status " + order.getStatus() + " does not allow refund");
            }

            long currentTotalRefunded = refundRepository.sumSuccessfulByOrder(orderId);
            long remainingRefundableCents = order.getPaymentAmountCents() - currentTotalRefunded;
            if (remainingRefundableCents <= 0) {
                throw new IllegalStateException("Order has no remaining refundable amount");
            }

            long refundAmountCents;
            if (request.amountCents() == null) {
                refundAmountCents = remainingRefundableCents;
            } else {
                refundAmountCents = request.amountCents();
                if (refundAmountCents <= 0) {
                    throw new InvalidRefundAmountException("Refund amount must be positive");
                }
                if (refundAmountCents > remainingRefundableCents) {
                    throw new InvalidRefundAmountException("Refund amount " + refundAmountCents
                            + " exceeds remaining refundable amount " + remainingRefundableCents);
                }
            }

            String refundId = "REF-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            String fingerprint = calculateFingerprint(request.merchantId(), orderId, request.amountCents());

            Refund refund = new Refund(
                    refundId,
                    orderId,
                    order.getMerchantId(),
                    idempotencyKey,
                    fingerprint,
                    refundAmountCents,
                    request.reason(),
                    request.reasonDescription(),
                    order.getPaymentChannel(),
                    order.getPaymentTransactionId(),
                    RefundStatus.PROCESSING
            );
            refundRepository.save(refund);

            PaymentGateway.GatewayResult result = paymentGateway.refund(
                    order.getPaymentChannel(),
                    order.getPaymentTransactionId(),
                    refundId,
                    refundAmountCents
            );

            if (result.success()) {
                refund.markSuccess();
                long newTotalRefunded = refundRepository.sumSuccessfulByOrder(orderId);
                if (newTotalRefunded >= order.getPaymentAmountCents()) {
                    order.setStatus(OrderStatus.REFUNDED);
                } else {
                    order.setStatus(OrderStatus.PARTIALLY_REFUNDED);
                }
                orderRepository.save(order);
                long remaining = Math.max(0, order.getPaymentAmountCents() - newTotalRefunded);
                return toResponse(refund, order.getStatus(), newTotalRefunded, remaining);
            } else {
                String failureCode = result.failureCode() != null ? result.failureCode() : "GATEWAY_ERROR";
                refund.markFailed(failureCode);
                throw new PaymentGatewayException(failureCode, "Payment gateway refund failed: " + failureCode);
            }
        }
    }

    private RefundResponse retryFailedRefund(Order order, Refund existing) {
        if (order.getStatus() == OrderStatus.REFUNDED) {
            throw new IllegalStateException("Order is already fully refunded");
        }
        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException("Order status " + order.getStatus() + " does not allow refund");
        }

        long currentTotalRefunded = refundRepository.sumSuccessfulByOrder(order.getOrderId());
        long remainingRefundableCents = order.getPaymentAmountCents() - currentTotalRefunded;
        if (existing.getAmountCents() > remainingRefundableCents) {
            throw new InvalidRefundAmountException("Retry amount " + existing.getAmountCents()
                    + " exceeds remaining refundable amount " + remainingRefundableCents);
        }

        existing.markProcessing();
        PaymentGateway.GatewayResult result = paymentGateway.refund(
                order.getPaymentChannel(),
                order.getPaymentTransactionId(),
                existing.getRefundId(),
                existing.getAmountCents()
        );

        if (result.success()) {
            existing.markSuccess();
            long newTotalRefunded = refundRepository.sumSuccessfulByOrder(order.getOrderId());
            if (newTotalRefunded >= order.getPaymentAmountCents()) {
                order.setStatus(OrderStatus.REFUNDED);
            } else {
                order.setStatus(OrderStatus.PARTIALLY_REFUNDED);
            }
            orderRepository.save(order);
            long remaining = Math.max(0, order.getPaymentAmountCents() - newTotalRefunded);
            return toResponse(existing, order.getStatus(), newTotalRefunded, remaining);
        } else {
            String failureCode = result.failureCode() != null ? result.failureCode() : "GATEWAY_ERROR";
            existing.markFailed(failureCode);
            throw new PaymentGatewayException(failureCode, "Payment gateway refund retry failed: " + failureCode);
        }
    }

    public List<RefundResponse> getRefundsForOrder(String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
        long totalRefunded = refundRepository.sumSuccessfulByOrder(orderId);
        long remaining = Math.max(0, order.getPaymentAmountCents() - totalRefunded);
        return refundRepository.findByOrderId(orderId).stream()
                .map(r -> toResponse(r, order.getStatus(), totalRefunded, remaining))
                .toList();
    }

    private String calculateFingerprint(String merchantId, String orderId, Long amountCents) {
        return merchantId + ":" + orderId + ":" + (amountCents == null ? "FULL" : amountCents);
    }

    private RefundResponse toResponse(Refund refund, OrderStatus orderStatus, long totalRefunded, long remaining) {
        return new RefundResponse(
                refund.getRefundId(),
                refund.getOrderId(),
                refund.getMerchantId(),
                refund.getAmountCents(),
                refund.getAmountCents() / 100.0,
                refund.getStatus(),
                orderStatus,
                totalRefunded,
                remaining,
                refund.getFailureCode(),
                refund.getCreatedAt()
        );
    }
}
