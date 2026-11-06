package com.acme.commerce;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyRefundServiceTest {

    private OrderRepository orderRepository;
    private RefundRepository refundRepository;
    private InMemoryPaymentGateway paymentGateway;
    private RefundService refundService;
    private LegacyRefundService legacyRefundService;

    @BeforeEach
    void setUp() {
        orderRepository = new OrderRepository();
        refundRepository = new RefundRepository();
        paymentGateway = new InMemoryPaymentGateway();
        paymentGateway.setTransactionFailing("PAY-C-300", false);
        refundService = new RefundService(orderRepository, refundRepository, paymentGateway);
        legacyRefundService = new LegacyRefundService(refundService);
    }

    @Test
    void legacyServiceHasExpectedPublicContract() {
        assertNotNull(LegacyRefundService.class);
        assertEquals(double.class, LegacyRefundService.class.getDeclaredMethods()[0].getReturnType());
    }

    @Test
    void testLegacyRefundAllFreshOrder() {
        Order order = new Order("LEG-1", "M-1", 10000, "ALIPAY", "PAY-LEG-1", OrderStatus.PAID);
        orderRepository.save(order);

        double refunded = legacyRefundService.refundAll("LEG-1", "M-1", "operator1");
        assertEquals(100.0, refunded);
        assertEquals(OrderStatus.REFUNDED, order.getStatus());
        assertEquals(10000L, refundRepository.sumSuccessfulByOrder("LEG-1"));
    }

    @Test
    void testLegacyRefundAllPartiallyRefundedOrder() {
        Order order = new Order("LEG-2", "M-1", 10000, "ALIPAY", "PAY-LEG-2", OrderStatus.PAID);
        orderRepository.save(order);

        // First partially refund 3000 cents (30.0)
        refundService.refund("LEG-2", new RefundRequest("M-1", 3000L, "K-PARTIAL", null, null, null));
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, order.getStatus());

        // Then call legacy refundAll: should refund remaining 7000 cents (70.0)
        double refunded = legacyRefundService.refundAll("LEG-2", "M-1", "operator2");
        assertEquals(70.0, refunded);
        assertEquals(OrderStatus.REFUNDED, order.getStatus());
        assertEquals(10000L, refundRepository.sumSuccessfulByOrder("LEG-2"));
    }
}
