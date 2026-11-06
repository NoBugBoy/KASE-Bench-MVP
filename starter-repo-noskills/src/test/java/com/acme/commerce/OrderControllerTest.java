package com.acme.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private InMemoryPaymentGateway paymentGateway;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void init() {
        // Reset test data
        refundRepository.clear();
        paymentGateway.setTransactionFailing("PAY-C-300", false);
        paymentGateway.setTransactionFailing("PAY-C-FAIL", false);
        orderRepository.save(new Order("TEST-100", "M-01", 10000, "ALIPAY", "PAY-A-100", OrderStatus.PAID));
        orderRepository.save(new Order("TEST-200", "M-01", 20000, "WECHAT", "PAY-W-200", OrderStatus.PAID));
        orderRepository.save(new Order("TEST-FAIL", "M-01", 10000, "CARD", "PAY-C-FAIL", OrderStatus.PAID));
    }

    @Test
    void testLegacyEndpointFullRefund() throws Exception {
        OrderController.LegacyRefundRequest request = new OrderController.LegacyRefundRequest("M-01", "legacy-operator");

        mockMvc.perform(post("/api/v1/orders/TEST-100/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("TEST-100"))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(100.0));
    }

    @Test
    void testLegacyEndpointAfterPartialRefundRefundsRemainingAmount() throws Exception {
        // Step 1: partial refund via new endpoint (5000 cents = 50.0)
        RefundRequest partialRequest = new RefundRequest("M-01", 5000L, "PARTIAL-KEY-1", null, null, "op");
        mockMvc.perform(post("/api/v1/orders/TEST-200/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(partialRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderStatus").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.amount").value(50.0))
                .andExpect(jsonPath("$.remainingAmountCents").value(15000));

        // Step 2: call legacy endpoint to refund remaining amount
        OrderController.LegacyRefundRequest legacyRequest = new OrderController.LegacyRefundRequest("M-01", "legacy-operator");
        mockMvc.perform(post("/api/v1/orders/TEST-200/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(legacyRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("TEST-200"))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(150.0)); // remaining 150.0 refunded
    }

    @Test
    void testLegacyEndpointSupportsOptionalPartialAmount() throws Exception {
        OrderController.LegacyRefundRequest request = new OrderController.LegacyRefundRequest(
                "M-01", "legacy-operator", 4000L, "LEGACY-PARTIAL-KEY", "Customer return", "desc");

        mockMvc.perform(post("/api/v1/orders/TEST-200/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("TEST-200"))
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(40.0));
    }

    @Test
    void testNewRefundEndpointSuccessAndGetList() throws Exception {
        RefundRequest request1 = new RefundRequest("M-01", 3000L, "NEW-K-1", "QUALITY", "Defect", "op1");

        mockMvc.perform(post("/api/v1/orders/TEST-100/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundId").isNotEmpty())
                .andExpect(jsonPath("$.orderId").value("TEST-100"))
                .andExpect(jsonPath("$.merchantId").value("M-01"))
                .andExpect(jsonPath("$.amountCents").value(3000))
                .andExpect(jsonPath("$.amount").value(30.0))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.orderStatus").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.totalRefundedAmountCents").value(3000))
                .andExpect(jsonPath("$.remainingAmountCents").value(7000));

        // Query refund list
        mockMvc.perform(get("/api/v1/orders/TEST-100/refunds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].orderId").value("TEST-100"));
    }

    @Test
    void testNewRefundEndpointValidationErrors() throws Exception {
        // Missing merchantId
        String missingMerchantJson = "{\"amountCents\": 1000}";
        mockMvc.perform(post("/api/v1/orders/TEST-100/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingMerchantJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Negative amount
        RefundRequest negativeAmount = new RefundRequest("M-01", -100L, null, null, null, null);
        mockMvc.perform(post("/api/v1/orders/TEST-100/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(negativeAmount)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void testNewRefundEndpointExceedsRemainingAmount() throws Exception {
        RefundRequest exceedRequest = new RefundRequest("M-01", 999999L, null, null, null, null);
        mockMvc.perform(post("/api/v1/orders/TEST-100/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(exceedRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REFUND_AMOUNT"));
    }

    @Test
    void testNewRefundEndpointOrderNotFound() throws Exception {
        RefundRequest request = new RefundRequest("M-01", 1000L, null, null, null, null);
        mockMvc.perform(post("/api/v1/orders/NON_EXISTENT_ORDER/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void testNewRefundEndpointIdempotencyConflict() throws Exception {
        RefundRequest r1 = new RefundRequest("M-01", 1000L, "IDEMP-CONFLICT-KEY", null, null, null);
        mockMvc.perform(post("/api/v1/orders/TEST-100/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(r1)))
                .andExpect(status().isCreated());

        RefundRequest r2 = new RefundRequest("M-01", 2000L, "IDEMP-CONFLICT-KEY", null, null, null);
        mockMvc.perform(post("/api/v1/orders/TEST-100/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(r2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void testGatewayFailureReturnsBadGateway() throws Exception {
        paymentGateway.setTransactionFailing("PAY-C-FAIL", true);
        RefundRequest request = new RefundRequest("M-01", 1000L, "FAIL-KEY-1", null, null, null);

        mockMvc.perform(post("/api/v1/orders/TEST-FAIL/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GATEWAY_ERROR"));
    }
}
