package com.acme.commerce;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RefundApplicationServiceTest {
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

        // Standard test order
        orderRepository.save(new Order("O-100", "M-01", 10000, "ALIPAY", "TX-100", OrderStatus.PAID));
    }

    @Test
    @DisplayName("验收场景 1 & 2: 多次部分退款与状态跃迁")
    void testMultiplePartialRefundsAndStatusTransition() {
        // 第一次退款 3000 分
        RefundCreateRequest req1 = new RefundCreateRequest("M-01", 3000L, "CUSTOMER_REQUEST", "退一件商品", "idemp-001");
        RefundResponse resp1 = refundService.refund("O-100", req1);

        assertEquals("SUCCESS", resp1.status());
        assertEquals("PARTIALLY_REFUNDED", resp1.orderStatus());
        assertEquals(3000L, resp1.totalRefundedCents());
        assertEquals(7000L, resp1.refundableCents());
        assertEquals("ALIPAY", resp1.paymentChannel());

        Order orderAfter1 = orderRepository.findById("O-100").orElseThrow();
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, orderAfter1.getStatus());

        // 第二次退款 7000 分（全额结清）
        RefundCreateRequest req2 = new RefundCreateRequest("M-01", 7000L, "DAMAGED", "剩余商品损坏", "idemp-002");
        RefundResponse resp2 = refundService.refund("O-100", req2);

        assertEquals("SUCCESS", resp2.status());
        assertEquals("REFUNDED", resp2.orderStatus());
        assertEquals(10000L, resp2.totalRefundedCents());
        assertEquals(0L, resp2.refundableCents());

        Order orderAfter2 = orderRepository.findById("O-100").orElseThrow();
        assertEquals(OrderStatus.REFUNDED, orderAfter2.getStatus());
    }

    @Test
    @DisplayName("验收场景 3: 已退款订单再次退款被拒绝 (ORDER_NOT_REFUNDABLE)")
    void testRefundOnFullyRefundedOrderRejected() {
        // 先退满 10000 分
        RefundCreateRequest fullReq = new RefundCreateRequest("M-01", 10000L, "CUSTOMER_REQUEST", "全部退款", "idemp-full");
        refundService.refund("O-100", fullReq);

        // 再次发起退款
        RefundCreateRequest extraReq = new RefundCreateRequest("M-01", 100L, "CUSTOMER_REQUEST", "再退100", "idemp-extra");
        RefundException ex = assertThrows(RefundException.class, () -> refundService.refund("O-100", extraReq));

        assertEquals("ORDER_NOT_REFUNDABLE", ex.getCode());
    }

    @Test
    @DisplayName("验收场景 4: 累计超额退款被拒绝 (REFUND_AMOUNT_EXCEEDED)")
    void testRefundAmountExceeded() {
        // 先退款 6000 分
        refundService.refund("O-100", new RefundCreateRequest("M-01", 6000L, "CUSTOMER_REQUEST", "退款6000", "idemp-6000"));

        // 再请求 5000 分（剩余可退 4000 分）
        RefundCreateRequest exceedReq = new RefundCreateRequest("M-01", 5000L, "CUSTOMER_REQUEST", "超额5000", "idemp-5000");
        RefundException ex = assertThrows(RefundException.class, () -> refundService.refund("O-100", exceedReq));

        assertEquals("REFUND_AMOUNT_EXCEEDED", ex.getCode());
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, orderRepository.findById("O-100").orElseThrow().getStatus());
        assertEquals(6000L, refundRepository.sumSuccessfulByOrder("O-100"));
    }

    @Test
    @DisplayName("验收场景 5: 渠道失败后订单状态和成功累计不变")
    void testPaymentChannelFailureKeepsStatusAndAmount() {
        orderRepository.save(new Order("FAIL-500", "M-01", 10000, "CARD", "TX-FAIL", OrderStatus.PAID));

        RefundCreateRequest req = new RefundCreateRequest("M-01", 3000L, "DAMAGED", "坏件", "idemp-fail-1");
        RefundException ex = assertThrows(RefundException.class, () -> refundService.refund("FAIL-500", req));

        assertEquals("PAYMENT_CHANNEL_FAILED", ex.getCode());
        Order order = orderRepository.findById("FAIL-500").orElseThrow();
        assertEquals(OrderStatus.PAID, order.getStatus());
        assertEquals(0L, refundRepository.sumSuccessfulByOrder("FAIL-500"));

        // 退款记录应被标记为 FAILED
        Refund failedRefund = refundRepository.findByIdempotency("M-01", "FAIL-500", "idemp-fail-1").orElseThrow();
        assertEquals(RefundStatus.FAILED, failedRefund.getStatus());
        assertEquals("CHANNEL_DECLINED", failedRefund.getFailureCode());
    }

    @Test
    @DisplayName("验收场景 6: 相同幂等键相同请求重试，返回原结果且不重复调用网关")
    void testIdempotencySameRequestDoesNotReinvokeGateway() {
        RefundCreateRequest req = new RefundCreateRequest("M-01", 3000L, "MISSING_ITEM", "少发一件", "idemp-same");
        RefundResponse resp1 = refundService.refund("O-100", req);

        int callCountAfterFirst = paymentGateway.getCallCount();
        assertEquals(1, callCountAfterFirst);

        // 相同入参重试
        RefundResponse resp2 = refundService.refund("O-100", req);

        assertEquals(resp1.refundId(), resp2.refundId());
        assertEquals(resp1.amountCents(), resp2.amountCents());
        assertEquals(resp1.status(), resp2.status());
        assertEquals(1, paymentGateway.getCallCount()); // 网关未被二次调用
    }

    @Test
    @DisplayName("验收场景 7: 相同幂等键不同请求参数返回 IDEMPOTENCY_CONFLICT")
    void testIdempotencyDifferentRequestReturnsConflict() {
        RefundCreateRequest req1 = new RefundCreateRequest("M-01", 3000L, "CUSTOMER_REQUEST", "退一件", "idemp-conflict");
        refundService.refund("O-100", req1);

        // 相同 idempotencyKey，不同金额
        RefundCreateRequest req2 = new RefundCreateRequest("M-01", 4000L, "CUSTOMER_REQUEST", "退一件", "idemp-conflict");
        RefundException ex1 = assertThrows(RefundException.class, () -> refundService.refund("O-100", req2));
        assertEquals("IDEMPOTENCY_CONFLICT", ex1.getCode());

        // 相同 idempotencyKey，不同原因
        RefundCreateRequest req3 = new RefundCreateRequest("M-01", 3000L, "DAMAGED", "退一件", "idemp-conflict");
        RefundException ex2 = assertThrows(RefundException.class, () -> refundService.refund("O-100", req3));
        assertEquals("IDEMPOTENCY_CONFLICT", ex2.getCode());
    }

    @Test
    @DisplayName("验收场景 9: 原支付渠道和交易号传入网关")
    void testGatewayCalledWithOriginalPaymentChannelAndTransaction() {
        orderRepository.save(new Order("O-WECHAT", "M-01", 5000, "WECHAT", "WX-TX-999", OrderStatus.PAID));

        RefundCreateRequest req = new RefundCreateRequest("M-01", 2000L, "CUSTOMER_REQUEST", "说明", "idemp-wx-1");
        RefundResponse resp = refundService.refund("O-WECHAT", req);

        assertEquals("WECHAT", resp.paymentChannel());
        Refund savedRefund = refundRepository.findByIdempotency("M-01", "O-WECHAT", "idemp-wx-1").orElseThrow();
        assertEquals("WECHAT", savedRefund.getPaymentChannel());
        assertEquals("WX-TX-999", savedRefund.getPaymentTransactionId());
    }

    @Test
    @DisplayName("验收场景 10: 旧整单退款接口复用领域服务完成剩余可退金额退款")
    void testLegacyRefundRemaining() {
        // 先部分退款 3000 分
        refundService.refund("O-100", new RefundCreateRequest("M-01", 3000L, "CUSTOMER_REQUEST", "退3000", "idemp-p30"));

        // 调用旧接口退款剩余（应退 7000 分 = 70.00 元）
        LegacyRefundService legacyService = new LegacyRefundService(orderRepository, paymentGateway, refundService);
        double refunded = legacyService.refundAll("O-100", "M-01", "operator-tom");

        assertEquals(70.00, refunded, 0.001);
        Order order = orderRepository.findById("O-100").orElseThrow();
        assertEquals(OrderStatus.REFUNDED, order.getStatus());
        assertEquals(10000L, refundRepository.sumSuccessfulByOrder("O-100"));
    }

    @Test
    @DisplayName("非合法订单状态不允许退款 (CREATED, PAYMENT_PENDING, CANCELLED)")
    void testNonRefundableOrderStatuses() {
        orderRepository.save(new Order("O-CREATED", "M-01", 1000, "ALIPAY", "T1", OrderStatus.CREATED));
        orderRepository.save(new Order("O-PENDING", "M-01", 1000, "ALIPAY", "T2", OrderStatus.PAYMENT_PENDING));
        orderRepository.save(new Order("O-CANCELLED", "M-01", 1000, "ALIPAY", "T3", OrderStatus.CANCELLED));

        RefundCreateRequest req = new RefundCreateRequest("M-01", 500L, "CUSTOMER_REQUEST", "退款", "idemp-stat");

        RefundException ex1 = assertThrows(RefundException.class, () -> refundService.refund("O-CREATED", req));
        assertEquals("ORDER_NOT_REFUNDABLE", ex1.getCode());

        RefundException ex2 = assertThrows(RefundException.class, () -> refundService.refund("O-PENDING", req));
        assertEquals("ORDER_NOT_REFUNDABLE", ex2.getCode());

        RefundException ex3 = assertThrows(RefundException.class, () -> refundService.refund("O-CANCELLED", req));
        assertEquals("ORDER_NOT_REFUNDABLE", ex3.getCode());
    }

    @Test
    @DisplayName("订单不存在返回 404 ORDER_NOT_FOUND")
    void testOrderNotFound() {
        RefundCreateRequest req = new RefundCreateRequest("M-01", 500L, "CUSTOMER_REQUEST", "退款", "idemp-notfound");
        RefundException ex = assertThrows(RefundException.class, () -> refundService.refund("NON_EXIST", req));
        assertEquals("ORDER_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("商户号不匹配返回 403 MERCHANT_MISMATCH")
    void testMerchantMismatch() {
        RefundCreateRequest req = new RefundCreateRequest("WRONG_M", 500L, "CUSTOMER_REQUEST", "退款", "idemp-mismatch");
        RefundException ex = assertThrows(RefundException.class, () -> refundService.refund("O-100", req));
        assertEquals("MERCHANT_MISMATCH", ex.getCode());
    }

    @Test
    @DisplayName("参数校验: amountCents, reason, OTHER 需必填 description, idempotencyKey 长度")
    void testParameterValidations() {
        // amountCents <= 0
        RefundException ex1 = assertThrows(RefundException.class, () ->
                refundService.refund("O-100", new RefundCreateRequest("M-01", 0L, "CUSTOMER_REQUEST", null, "idemp-12345")));
        assertEquals("INVALID_REFUND_REQUEST", ex1.getCode());

        // 无效 reason
        RefundException ex2 = assertThrows(RefundException.class, () ->
                refundService.refund("O-100", new RefundCreateRequest("M-01", 100L, "UNKNOWN_REASON", null, "idemp-12345")));
        assertEquals("INVALID_REFUND_REQUEST", ex2.getCode());

        // reason 为 OTHER 时 description 缺失
        RefundException ex3 = assertThrows(RefundException.class, () ->
                refundService.refund("O-100", new RefundCreateRequest("M-01", 100L, "OTHER", null, "idemp-12345")));
        assertEquals("INVALID_REFUND_REQUEST", ex3.getCode());

        // idempotencyKey 长度不足 8 位
        RefundException ex4 = assertThrows(RefundException.class, () ->
                refundService.refund("O-100", new RefundCreateRequest("M-01", 100L, "DAMAGED", null, "short")));
        assertEquals("INVALID_REFUND_REQUEST", ex4.getCode());

        // merchantId 缺失
        RefundException ex5 = assertThrows(RefundException.class, () ->
                refundService.refund("O-100", new RefundCreateRequest(null, 100L, "DAMAGED", null, "idemp-12345")));
        assertEquals("INVALID_REFUND_REQUEST", ex5.getCode());
    }

    @Test
    @DisplayName("查询订单可退摘要 getRefundableSummary")
    void testGetRefundableSummary() {
        refundService.refund("O-100", new RefundCreateRequest("M-01", 2500L, "CUSTOMER_REQUEST", null, "idemp-sum1"));

        RefundableSummaryResponse summary = refundService.getRefundableSummary("O-100", "M-01");
        assertEquals("O-100", summary.orderId());
        assertEquals(10000L, summary.paymentAmountCents());
        assertEquals(2500L, summary.totalRefundedCents());
        assertEquals(7500L, summary.refundableCents());
        assertEquals("PARTIALLY_REFUNDED", summary.orderStatus());
        assertEquals("ALIPAY", summary.paymentChannel());
    }
}
