package com.acme.commerce;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RefundServiceTest {

    private OrderRepository orderRepository;
    private RefundRepository refundRepository;
    private InMemoryPaymentGateway paymentGateway;
    private RefundService refundService;

    @BeforeEach
    void setUp() {
        orderRepository = new OrderRepository();
        refundRepository = new RefundRepository();
        paymentGateway = new InMemoryPaymentGateway();
        // Clear default failure in gateway for clean tests
        paymentGateway.setTransactionFailing("PAY-C-300", false);
        refundService = new RefundService(orderRepository, refundRepository, paymentGateway);
    }

    @Test
    void testNormalPartialRefund() {
        Order order = new Order("O-1", "M-1", 10000, "ALIPAY", "TX-1", OrderStatus.PAID);
        orderRepository.save(order);

        RefundResponse response = refundService.refund("O-1", new RefundRequest("M-1", 3000L, "K-1", "PARTIAL", "Partial return", "op1"));

        assertNotNull(response.refundId());
        assertEquals(3000L, response.amountCents());
        assertEquals(30.0, response.amount());
        assertEquals(RefundStatus.SUCCESS, response.status());
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, response.orderStatus());
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, order.getStatus());
        assertEquals(3000L, response.totalRefundedAmountCents());
        assertEquals(7000L, response.remainingAmountCents());
    }

    @Test
    void testMultiplePartialRefundsUntilFull() {
        Order order = new Order("O-2", "M-1", 10000, "WECHAT", "TX-2", OrderStatus.PAID);
        orderRepository.save(order);

        // Step 1: refund 3000
        RefundResponse r1 = refundService.refund("O-2", new RefundRequest("M-1", 3000L, "K-1", null, null, null));
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, r1.orderStatus());
        assertEquals(7000L, r1.remainingAmountCents());

        // Step 2: refund 4000
        RefundResponse r2 = refundService.refund("O-2", new RefundRequest("M-1", 4000L, "K-2", null, null, null));
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, r2.orderStatus());
        assertEquals(7000L, r2.totalRefundedAmountCents());
        assertEquals(3000L, r2.remainingAmountCents());

        // Step 3: refund remaining 3000
        RefundResponse r3 = refundService.refund("O-2", new RefundRequest("M-1", 3000L, "K-3", null, null, null));
        assertEquals(OrderStatus.REFUNDED, r3.orderStatus());
        assertEquals(OrderStatus.REFUNDED, order.getStatus());
        assertEquals(10000L, r3.totalRefundedAmountCents());
        assertEquals(0L, r3.remainingAmountCents());
    }

    @Test
    void testBoundaryExactAmountFullRefund() {
        Order order = new Order("O-3", "M-1", 5000, "ALIPAY", "TX-3", OrderStatus.PAID);
        orderRepository.save(order);

        RefundResponse response = refundService.refund("O-3", new RefundRequest("M-1", 5000L, "K-1", null, null, null));
        assertEquals(OrderStatus.REFUNDED, response.orderStatus());
        assertEquals(5000L, response.totalRefundedAmountCents());
        assertEquals(0L, response.remainingAmountCents());
    }

    @Test
    void testBoundaryOneCentRefund() {
        Order order = new Order("O-4", "M-1", 100, "CARD", "TX-4", OrderStatus.PAID);
        orderRepository.save(order);

        RefundResponse response = refundService.refund("O-4", new RefundRequest("M-1", 1L, "K-1", null, null, null));
        assertEquals(1L, response.amountCents());
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, response.orderStatus());
        assertEquals(99L, response.remainingAmountCents());
    }

    @Test
    void testExceedsRemainingAmountThrowsException() {
        Order order = new Order("O-5", "M-1", 5000, "CARD", "TX-5", OrderStatus.PAID);
        orderRepository.save(order);

        // Exceeds order total
        assertThrows(InvalidRefundAmountException.class, () ->
                refundService.refund("O-5", new RefundRequest("M-1", 5001L, "K-1", null, null, null)));

        // Exceeds remaining after partial
        refundService.refund("O-5", new RefundRequest("M-1", 4000L, "K-1", null, null, null));
        assertThrows(InvalidRefundAmountException.class, () ->
                refundService.refund("O-5", new RefundRequest("M-1", 1001L, "K-2", null, null, null)));
    }

    @Test
    void testZeroOrNegativeAmountThrowsException() {
        Order order = new Order("O-6", "M-1", 5000, "CARD", "TX-6", OrderStatus.PAID);
        orderRepository.save(order);

        assertThrows(InvalidRefundAmountException.class, () ->
                refundService.refund("O-6", new RefundRequest("M-1", 0L, "K-1", null, null, null)));

        assertThrows(InvalidRefundAmountException.class, () ->
                refundService.refund("O-6", new RefundRequest("M-1", -500L, "K-2", null, null, null)));
    }

    @Test
    void testMerchantMismatchThrowsException() {
        Order order = new Order("O-7", "M-CORRECT", 5000, "CARD", "TX-7", OrderStatus.PAID);
        orderRepository.save(order);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                refundService.refund("O-7", new RefundRequest("M-WRONG", 1000L, "K-1", null, null, null)));
        assertEquals("merchant mismatch", ex.getMessage());
    }

    @Test
    void testOrderNotFoundThrowsException() {
        assertThrows(OrderNotFoundException.class, () ->
                refundService.refund("NON_EXISTENT", new RefundRequest("M-1", 1000L, "K-1", null, null, null)));
    }

    @Test
    void testInvalidOrderStatusThrowsException() {
        Order orderCreated = new Order("O-C", "M-1", 5000, "CARD", "TX-C", OrderStatus.CREATED);
        orderRepository.save(orderCreated);
        assertThrows(IllegalStateException.class, () ->
                refundService.refund("O-C", new RefundRequest("M-1", 1000L, "K-1", null, null, null)));

        Order orderCancelled = new Order("O-CANCEL", "M-1", 5000, "CARD", "TX-CN", OrderStatus.CANCELLED);
        orderRepository.save(orderCancelled);
        assertThrows(IllegalStateException.class, () ->
                refundService.refund("O-CANCEL", new RefundRequest("M-1", 1000L, "K-2", null, null, null)));

        Order orderRefunded = new Order("O-REF", "M-1", 5000, "CARD", "TX-RF", OrderStatus.REFUNDED);
        orderRepository.save(orderRefunded);
        assertThrows(IllegalStateException.class, () ->
                refundService.refund("O-REF", new RefundRequest("M-1", 1000L, "K-3", null, null, null)));
    }

    @Test
    void testIdempotencyDuplicateRequestReturnsSameResponse() {
        Order order = new Order("O-8", "M-1", 10000, "ALIPAY", "TX-8", OrderStatus.PAID);
        orderRepository.save(order);

        RefundRequest request = new RefundRequest("M-1", 3000L, "IDEMP-KEY-1", "REASON", "DESC", "OP");

        int initialCalls = paymentGateway.getCallCount();
        RefundResponse first = refundService.refund("O-8", request);
        assertEquals(initialCalls + 1, paymentGateway.getCallCount());
        assertEquals(RefundStatus.SUCCESS, first.status());

        // Duplicate call with same idempotencyKey
        RefundResponse second = refundService.refund("O-8", request);
        // Payment gateway call count must NOT increment
        assertEquals(initialCalls + 1, paymentGateway.getCallCount());
        assertEquals(first.refundId(), second.refundId());
        assertEquals(first.amountCents(), second.amountCents());
        assertEquals(first.status(), second.status());
    }

    @Test
    void testIdempotencyConflictWithDifferentParameters() {
        Order order = new Order("O-9", "M-1", 10000, "ALIPAY", "TX-9", OrderStatus.PAID);
        orderRepository.save(order);

        RefundRequest request1 = new RefundRequest("M-1", 3000L, "IDEMP-CONFLICT", null, null, null);
        refundService.refund("O-9", request1);

        RefundRequest request2 = new RefundRequest("M-1", 5000L, "IDEMP-CONFLICT", null, null, null);
        assertThrows(IdempotencyConflictException.class, () ->
                refundService.refund("O-9", request2));
    }

    @Test
    void testGatewayFailureAndRetryWithSameIdempotencyKey() {
        Order order = new Order("O-10", "M-1", 10000, "ALIPAY", "TX-10", OrderStatus.PAID);
        orderRepository.save(order);

        // Make gateway fail
        paymentGateway.setFailNext(true);

        RefundRequest request = new RefundRequest("M-1", 4000L, "RETRY-KEY-1", null, null, null);

        // First attempt fails
        assertThrows(PaymentGatewayException.class, () -> refundService.refund("O-10", request));
        assertEquals(OrderStatus.PAID, order.getStatus());
        assertEquals(0L, refundRepository.sumSuccessfulByOrder("O-10"));

        // Retry with same idempotency key (gateway now succeeds)
        RefundResponse response = refundService.refund("O-10", request);
        assertEquals(RefundStatus.SUCCESS, response.status());
        assertEquals(4000L, response.amountCents());
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, order.getStatus());
        assertEquals(4000L, refundRepository.sumSuccessfulByOrder("O-10"));
    }

    @Test
    void testConcurrencyProtectionAgainstOverRefund() throws InterruptedException {
        Order order = new Order("O-CONC", "M-1", 10000, "ALIPAY", "TX-CONC", OrderStatus.PAID);
        orderRepository.save(order);

        int threadCount = 10;
        long refundPerThread = 2000L; // 10 * 2000 = 20000, which exceeds 10000 limit
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final String key = "CONC-KEY-" + i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    refundService.refund("O-CONC", new RefundRequest("M-1", refundPerThread, key, null, null, null));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Exactly 5 can succeed (5 * 2000 = 10000)
        assertEquals(5, successCount.get());
        assertEquals(5, failCount.get());
        assertEquals(10000L, refundRepository.sumSuccessfulByOrder("O-CONC"));
        assertEquals(OrderStatus.REFUNDED, order.getStatus());
    }
}
