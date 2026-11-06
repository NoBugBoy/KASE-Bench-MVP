package com.acme.commerce;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class InMemoryPaymentGateway implements PaymentGateway {
    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicBoolean failNext = new AtomicBoolean(false);
    private final Set<String> failingTransactionIds = ConcurrentHashMap.newKeySet();

    public InMemoryPaymentGateway() {
        // Complete the failure simulation for SeedData's O-FAIL-300
        failingTransactionIds.add("PAY-C-300");
    }

    @Override
    public GatewayResult refund(String paymentChannel, String paymentTransactionId, String refundId, long amountCents) {
        callCount.incrementAndGet();
        if (failNext.getAndSet(false) || (paymentTransactionId != null && failingTransactionIds.contains(paymentTransactionId))) {
            return new GatewayResult(false, "GATEWAY_REJECTED");
        }
        return new GatewayResult(true, null);
    }

    public int getCallCount() { return callCount.get(); }

    public void setFailNext(boolean fail) {
        this.failNext.set(fail);
    }

    public void setTransactionFailing(String paymentTransactionId, boolean failing) {
        if (failing) {
            failingTransactionIds.add(paymentTransactionId);
        } else {
            failingTransactionIds.remove(paymentTransactionId);
        }
    }
}
