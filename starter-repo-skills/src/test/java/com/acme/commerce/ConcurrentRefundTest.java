package com.acme.commerce;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentRefundTest {
    private OrderRepository orderRepository;
    private RefundRepository refundRepository;
    private InMemoryPaymentGateway paymentGateway;
    private RefundApplicationService refundService;

    @BeforeEach
    void setUp() {
        orderRepository = new OrderRepository();
        refundRepository = new RefundRepository();
        paymentGateway = new InMemoryPaymentGateway();
        refundService = new RefundApplicationService(orderRepository, refundRepository, paymentGateway);

        orderRepository.save(new Order("O-CONC-1", "M-01", 10000, "ALIPAY", "TX-1", OrderStatus.PAID));
        orderRepository.save(new Order("O-CONC-2", "M-01", 10000, "WECHAT", "TX-2", OrderStatus.PAID));
    }

    @Test
    @DisplayName("验收场景 8: 两个并发退款请求的成功金额总和不能超过订单支付金额")
    void testConcurrentRefundsNeverExceedPaymentAmount() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger exceededOrRejectedCount = new AtomicInteger();

        // 10 个线程争抢同一个订单的 10000 分，每个线程请求 2000 分（最多只能 5 个成功）
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    RefundCreateRequest req = new RefundCreateRequest(
                            "M-01", 2000L, "CUSTOMER_REQUEST", "并发抢退款", "idemp-conc-" + index
                    );
                    refundService.refund("O-CONC-1", req);
                    successCount.incrementAndGet();
                } catch (RefundException ex) {
                    if ("REFUND_AMOUNT_EXCEEDED".equals(ex.getCode())
                            || "ORDER_NOT_REFUNDABLE".equals(ex.getCode())
                            || "REFUND_IN_PROGRESS".equals(ex.getCode())) {
                        exceededOrRejectedCount.incrementAndGet();
                    } else {
                        ex.printStackTrace();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        long totalRefunded = refundRepository.sumSuccessfulByOrder("O-CONC-1");
        assertTrue(totalRefunded <= 10000L, "Total refunded must not exceed payment amount, actual: " + totalRefunded);
        assertTrue(successCount.get() <= 5, "Successful refunds cannot exceed 5");
        assertEquals(threadCount, successCount.get() + exceededOrRejectedCount.get());
    }

    @Test
    @DisplayName("并发隔离性: 不同订单之间的退款不应互相阻塞")
    void testDifferentOrdersDoNotBlockEachOther() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        Future<RefundResponse> f1 = executor.submit(() -> {
            startLatch.await();
            return refundService.refund("O-CONC-1",
                    new RefundCreateRequest("M-01", 1000L, "CUSTOMER_REQUEST", "退款1", "idemp-diff-1"));
        });
        Future<RefundResponse> f2 = executor.submit(() -> {
            startLatch.await();
            return refundService.refund("O-CONC-2",
                    new RefundCreateRequest("M-01", 1000L, "CUSTOMER_REQUEST", "退款2", "idemp-diff-2"));
        });

        startLatch.countDown();
        RefundResponse r1 = f1.get(3, TimeUnit.SECONDS);
        RefundResponse r2 = f2.get(3, TimeUnit.SECONDS);

        assertEquals("SUCCESS", r1.status());
        assertEquals("SUCCESS", r2.status());
        executor.shutdown();
    }

    @Test
    @DisplayName("并发相同幂等键请求穿透保护: 多个相同幂等键并发只产生一次成功退款")
    void testConcurrentSameIdempotencyKey() throws Exception {
        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<RefundResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return refundService.refund("O-CONC-1",
                        new RefundCreateRequest("M-01", 2000L, "CUSTOMER_REQUEST", "相同请求", "idemp-same-concurrent"));
            }));
        }

        startLatch.countDown();
        String refundId = null;
        for (Future<RefundResponse> f : futures) {
            RefundResponse resp = f.get(3, TimeUnit.SECONDS);
            assertEquals("SUCCESS", resp.status());
            if (refundId == null) {
                refundId = resp.refundId();
            } else {
                assertEquals(refundId, resp.refundId());
            }
        }
        executor.shutdown();

        // 仅产生一笔 2000 分退款
        assertEquals(2000L, refundRepository.sumSuccessfulByOrder("O-CONC-1"));
    }
}
