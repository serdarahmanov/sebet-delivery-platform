package com.sebet.order_service.internal.controller;

import com.sebet.order_service.config.InternalAuthInterceptor;
import com.sebet.order_service.config.WebMvcConfig;
import com.sebet.order_service.internal.dto.response.UpdateAfterProposalResponse;
import com.sebet.order_service.internal.service.InternalDriverAssignmentService;
import com.sebet.order_service.internal.service.InternalOrderLifecycleService;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.exception.GlobalExceptionHandler;
import com.sebet.order_service.shared.exception.OrderInvalidTransitionException;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = InternalOrderController.class,
        properties = "order-service.internal.secret=test-internal-secret"
)
@Import({
        GlobalExceptionHandler.class,
        WebMvcConfig.class,
        InternalAuthInterceptor.class
})
@ImportAutoConfiguration
class InternalOrderControllerTest {

    private static final String ORDER_ID = "8be6c1f8-f8b0-45d4-900b-1af2353441b7";
    private static final String PROPOSAL_ID = "e78b2e0f-71e3-47cf-9b7a-d2da4c2f0e26";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InternalDriverAssignmentService driverAssignmentService;

    @MockitoBean
    private InternalOrderLifecycleService orderLifecycleService;

    @Test
    void updateAfterProposalReturnsOkAndDelegatesToService() throws Exception {
        when(orderLifecycleService.updateAfterProposal(eq(ORDER_ID), any(), eq("idem-1")))
                .thenReturn(new UpdateAfterProposalResponse(
                        ORDER_ID,
                        "CONFIRMED",
                        PROPOSAL_ID,
                        "2026-07-10T10:00Z"
                ));

        mockMvc.perform(post("/api/v1/internal/orders/{orderId}/update-after-proposal", ORDER_ID)
                        .header("X-Internal-Key", "test-internal-secret")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.proposalId").value(PROPOSAL_ID));

        verify(orderLifecycleService).updateAfterProposal(eq(ORDER_ID), any(), eq("idem-1"));
    }

    @Test
    void updateAfterProposalRejectsMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/internal/orders/{orderId}/update-after-proposal", ORDER_ID)
                        .header("X-Internal-Key", "test-internal-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"))
                .andExpect(jsonPath("$.message").value("Required header is missing: Idempotency-Key"));

        verify(orderLifecycleService, never()).updateAfterProposal(any(), any(), any());
    }

    @Test
    void updateAfterProposalRejectsInvalidBody() throws Exception {
        String invalidBody = """
                {
                  "proposalId": "%s",
                  "promoCalculationId": "calc-1",
                  "currency": "UZS",
                  "subtotalAmount": 10000.00,
                  "itemDiscountAmount": 0.00,
                  "orderDiscountAmount": 0.00,
                  "deliveryFeeAmount": 3000.00,
                  "serviceFeeAmount": 0.00,
                  "smallOrderFeeAmount": 0.00,
                  "totalAmount": 13000.00,
                  "items": []
                }
                """.formatted(PROPOSAL_ID);

        mockMvc.perform(post("/api/v1/internal/orders/{orderId}/update-after-proposal", ORDER_ID)
                        .header("X-Internal-Key", "test-internal-secret")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("items: must not be empty"));

        verify(orderLifecycleService, never()).updateAfterProposal(any(), any(), any());
    }

    @Test
    void updateAfterProposalReturnsNotFoundWhenServiceCannotFindOrderOrProposal() throws Exception {
        when(orderLifecycleService.updateAfterProposal(eq(ORDER_ID), any(), eq("idem-1")))
                .thenThrow(new OrderNotFoundException(ORDER_ID));

        mockMvc.perform(post("/api/v1/internal/orders/{orderId}/update-after-proposal", ORDER_ID)
                        .header("X-Internal-Key", "test-internal-secret")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void updateAfterProposalReturnsConflictWhenOrderStateIsWrong() throws Exception {
        when(orderLifecycleService.updateAfterProposal(eq(ORDER_ID), any(), eq("idem-1")))
                .thenThrow(new OrderInvalidTransitionException(
                        ORDER_ID,
                        OrderStatus.CONFIRMED,
                        OrderStatus.CONFIRMED
                ));

        mockMvc.perform(post("/api/v1/internal/orders/{orderId}/update-after-proposal", ORDER_ID)
                        .header("X-Internal-Key", "test-internal-secret")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_INVALID_TRANSITION"));
    }

    private String validRequestJson() {
        return """
                {
                  "proposalId": "%s",
                  "promoCalculationId": "calc-1",
                  "currency": "UZS",
                  "subtotalAmount": 10000.00,
                  "itemDiscountAmount": 1000.00,
                  "orderDiscountAmount": 500.00,
                  "deliveryFeeAmount": 3000.00,
                  "serviceFeeAmount": 0.00,
                  "smallOrderFeeAmount": 0.00,
                  "totalAmount": 11500.00,
                  "selectedPromoCodes": ["PROMO500"],
                  "items": [
                    {
                      "productId": "p1",
                      "quantity": 1.000,
                      "unit": "PCS",
                      "unitPriceAmount": 10000.00,
                      "grossAmount": 10000.00,
                      "discountAmount": 1000.00,
                      "netAmount": 9000.00
                    }
                  ]
                }
                """.formatted(PROPOSAL_ID);
    }
}
