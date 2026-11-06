package com.acme.commerce;

import org.springframework.stereotype.Repository;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
public class RefundRepository {
    private final List<Refund> refunds = new CopyOnWriteArrayList<>();
    public void save(Refund refund) { refunds.add(refund); }
    public Optional<Refund> findByIdempotency(String merchantId, String orderId, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return refunds.stream().filter(r -> Objects.equals(r.getMerchantId(), merchantId)
                && Objects.equals(r.getOrderId(), orderId) && Objects.equals(r.getIdempotencyKey(), key)).findFirst();
    }
    public List<Refund> findByOrderId(String orderId) {
        return refunds.stream().filter(r -> Objects.equals(r.getOrderId(), orderId)).toList();
    }
    public long sumSuccessfulByOrder(String orderId) {
        return refunds.stream().filter(r -> r.getOrderId().equals(orderId) && r.getStatus() == RefundStatus.SUCCESS)
                .mapToLong(Refund::getAmountCents).sum();
    }
    public List<Refund> findAll() { return List.copyOf(refunds); }
    public void clear() { refunds.clear(); }
}
