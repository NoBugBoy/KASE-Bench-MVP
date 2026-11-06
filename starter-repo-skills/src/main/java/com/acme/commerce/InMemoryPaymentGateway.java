package com.acme.commerce;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class InMemoryPaymentGateway implements PaymentGateway {
    private final AtomicInteger callCount = new AtomicInteger();
    private RefundRepository refundRepository;

    @Autowired(required = false)
    public InMemoryPaymentGateway(RefundRepository refundRepository) {
        this.refundRepository = refundRepository;
    }

    public InMemoryPaymentGateway() {
    }

    public void setRefundRepository(RefundRepository refundRepository) {
        this.refundRepository = refundRepository;
    }

    @Override
    public GatewayResult refund(String paymentChannel, String paymentTransactionId, String refundId, long amountCents) {
        callCount.incrementAndGet();

        boolean shouldFail = false;
        if (refundId != null && refundId.toUpperCase().contains("FAIL")) {
            shouldFail = true;
        } else if (paymentTransactionId != null && paymentTransactionId.toUpperCase().contains("FAIL")) {
            shouldFail = true;
        } else if (refundRepository != null) {
            boolean orderFail = refundRepository.findAll().stream()
                    .filter(r -> r.getRefundId().equals(refundId))
                    .map(Refund::getOrderId)
                    .anyMatch(id -> id != null && id.toUpperCase().contains("FAIL"));
            if (orderFail) {
                shouldFail = true;
            }
        }

        if (shouldFail) {
            return new GatewayResult(false, "CHANNEL_DECLINED");
        }
        return new GatewayResult(true, null);
    }

    public int getCallCount() { return callCount.get(); }
}
