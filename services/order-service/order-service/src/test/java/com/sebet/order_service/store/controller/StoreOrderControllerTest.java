package com.sebet.order_service.store.controller;

import com.sebet.order_service.shared.exception.GlobalExceptionHandler;
import com.sebet.order_service.store.dto.response.StoreCancelActiveProposalResponse;
import com.sebet.order_service.store.service.StoreOrderLifecycleService;
import com.sebet.order_service.store.service.StoreOrderQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = StoreOrderController.class,
        properties = "order-service.internal.secret=test-internal-secret"
)
@Import(GlobalExceptionHandler.class)
class StoreOrderControllerTest {

    private static final String ORDER_ID = "8be6c1f8-f8b0-45d4-900b-1af2353441b7";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreOrderLifecycleService storeOrderLifecycleService;

    @MockitoBean
    private StoreOrderQueryService storeOrderQueryService;

    @Test
    void cancelActiveProposalReturnsOkAndDelegatesToService() throws Exception {
        when(storeOrderLifecycleService.cancelActiveProposal("store-1", ORDER_ID, "idem-1"))
                .thenReturn(new StoreCancelActiveProposalResponse(
                        ORDER_ID,
                        "CONFIRMED",
                        "2026-07-10T10:00Z"
                ));

        mockMvc.perform(post("/api/v1/store/orders/{orderId}/cancel-active-proposal", ORDER_ID)
                        .header("X-Store-Id", "store-1")
                        .header("Idempotency-Key", "idem-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.cancelledAt").value("2026-07-10T10:00Z"));

        verify(storeOrderLifecycleService).cancelActiveProposal("store-1", ORDER_ID, "idem-1");
    }
}
