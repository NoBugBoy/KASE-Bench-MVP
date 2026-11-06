package com.acme.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OrderRefundIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("端到端: 新接口部分退款成功并返回正确结构")
    void testPartialRefundSuccess() throws Exception {
        RefundCreateRequest request = new RefundCreateRequest(
                "M-01", 3000L, "CUSTOMER_REQUEST", "退一件商品", "idemp-int-001"
        );

        mockMvc.perform(post("/api/v1/orders/O-100/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("O-100"))
                .andExpect(jsonPath("$.merchantId").value("M-01"))
                .andExpect(jsonPath("$.amountCents").value(3000))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.orderStatus").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.totalRefundedCents").value(3000))
                .andExpect(jsonPath("$.refundableCents").value(7000))
                .andExpect(jsonPath("$.paymentChannel").value("ALIPAY"))
                .andExpect(jsonPath("$.refundId").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("端到端: 查询可退摘要 GET /api/v1/orders/{orderId}/refundable")
    void testGetRefundableSummaryEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/orders/O-200/refundable")
                        .param("merchantId", "M-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("O-200"))
                .andExpect(jsonPath("$.paymentAmountCents").value(20000))
                .andExpect(jsonPath("$.orderStatus").value("PAID"))
                .andExpect(jsonPath("$.paymentChannel").value("WECHAT"));
    }

    @Test
    @DisplayName("端到端: 404 订单不存在")
    void testOrderNotFoundEndpoint() throws Exception {
        RefundCreateRequest request = new RefundCreateRequest(
                "M-01", 1000L, "CUSTOMER_REQUEST", "退款", "idemp-int-404"
        );

        mockMvc.perform(post("/api/v1/orders/O-NON-EXISTENT/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("端到端: 403 商户不匹配")
    void testMerchantMismatchEndpoint() throws Exception {
        RefundCreateRequest request = new RefundCreateRequest(
                "WRONG-MERCHANT", 1000L, "CUSTOMER_REQUEST", "退款", "idemp-int-403"
        );

        mockMvc.perform(post("/api/v1/orders/O-400/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MERCHANT_MISMATCH"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("端到端: 422 参数校验错误统一结构")
    void testInvalidRefundRequestEndpoint() throws Exception {
        RefundCreateRequest request = new RefundCreateRequest(
                "M-01", -100L, "INVALID_REASON", "退款", "short"
        );

        mockMvc.perform(post("/api/v1/orders/O-100/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REFUND_REQUEST"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("端到端: 502 支付渠道失败")
    void testPaymentChannelFailedEndpoint() throws Exception {
        RefundCreateRequest request = new RefundCreateRequest(
                "M-01", 1000L, "DAMAGED", "坏件", "idemp-int-fail"
        );

        mockMvc.perform(post("/api/v1/orders/O-FAIL-300/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYMENT_CHANNEL_FAILED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("端到端: 旧接口兼容 POST /api/v1/orders/{orderId}/refund")
    void testLegacyRefundEndpointCompatibility() throws Exception {
        // 创建一个用于旧接口测试的独立订单
        orderRepository.save(new Order("O-LEGACY-TEST", "M-01", 8000, "ALIPAY", "TX-LEGACY", OrderStatus.PAID));

        OrderController.LegacyRefundRequest legacyRequest = new OrderController.LegacyRefundRequest("M-01", "support-agent-01");

        mockMvc.perform(post("/api/v1/orders/O-LEGACY-TEST/refund")
                        .header("X-Request-Id", "req-legacy-custom-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(legacyRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("O-LEGACY-TEST"))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(80.00));
    }
}
