package com.acme.commerce;

import org.springframework.stereotype.Repository;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
public class RefundRepository {
    private final List<Refund> refunds = new CopyOnWriteArrayList<>();

    public synchronized void save(Refund refund) {
        for (int i = 0; i < refunds.size(); i++) {
            if (refunds.get(i).getRefundId().equals(refund.getRefundId())) {
                refunds.set(i, refund);
                return;
            }
        }
        refunds.add(refund);
    }

    public Optional<Refund> findByIdempotency(String merchantId, String orderId, String key) {
        return refunds.stream().filter(r -> r.getMerchantId().equals(merchantId)
                && r.getOrderId().equals(orderId) && r.getIdempotencyKey().equals(key)).findFirst();
    }

    public long sumSuccessfulByOrder(String orderId) {
        return refunds.stream().filter(r -> r.getOrderId().equals(orderId) && r.getStatus() == RefundStatus.SUCCESS)
                .mapToLong(Refund::getAmountCents).sum();
    }

    public boolean hasProcessingRefund(String orderId) {
        return refunds.stream().anyMatch(r -> r.getOrderId().equals(orderId) && r.getStatus() == RefundStatus.PROCESSING);
    }

    public List<Refund> findAll() { return List.copyOf(refunds); }
}
