package com.sebet.order_service.internal.controller;

import com.sebet.order_service.internal.dto.request.AssignDriverRequest;
import com.sebet.order_service.internal.dto.request.SystemCancelOrderRequest;
import com.sebet.order_service.internal.dto.request.UnassignDriverRequest;
import com.sebet.order_service.internal.dto.response.ActivateScheduledOrderResponse;
import com.sebet.order_service.internal.dto.response.AssignDriverResponse;
import com.sebet.order_service.internal.dto.response.CancelActiveProposalResponse;
import com.sebet.order_service.internal.dto.response.CancelProposalResponse;
import com.sebet.order_service.internal.dto.response.SystemCancelOrderResponse;
import com.sebet.order_service.internal.dto.response.UnassignDriverResponse;
import com.sebet.order_service.internal.service.InternalDriverAssignmentService;
import com.sebet.order_service.internal.service.InternalOrderLifecycleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal service-to-service order endpoints.
 *
 * All endpoints require a valid X-Internal-Key header, enforced globally by
 * InternalAuthInterceptor. These endpoints are not exposed to customers, stores,
 * or drivers.
 */
@RestController
@RequestMapping("/api/v1/internal/orders")
@RequiredArgsConstructor
public class InternalOrderController {

    private final InternalDriverAssignmentService driverAssignmentService;
    private final InternalOrderLifecycleService orderLifecycleService;

    /**
     * Assigns a driver to an order.
     *
     * Requires Idempotency-Key. Valid for non-terminal orders.
     */
    @PostMapping("/{orderId}/assign-driver")
    public ResponseEntity<AssignDriverResponse> assignDriver(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String orderId,
            @RequestBody @Valid AssignDriverRequest request
    ) {
        return ResponseEntity.ok(driverAssignmentService.assignDriver(orderId, request, idempotencyKey));
    }

    /**
     * Removes the currently assigned driver from an order without changing status.
     *
     * Requires Idempotency-Key. Valid for non-terminal orders.
     */
    @PostMapping("/{orderId}/unassign-driver")
    public ResponseEntity<UnassignDriverResponse> unassignDriver(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String orderId,
            @RequestBody @Valid UnassignDriverRequest request
    ) {
        return ResponseEntity.ok(driverAssignmentService.unassignDriver(orderId, request, idempotencyKey));
    }

    /**
     * Force-cancels an order on behalf of admin/support tooling.
     *
     * This is an explicit admin override. It can cancel any order that has not
     * already reached DELIVERED. Delivered orders should be handled by refund or
     * settlement adjustment workflows instead of rewriting lifecycle status.
     *
     * Requires Idempotency-Key.
     */
    @PostMapping("/{orderId}/admin-cancel")
    public ResponseEntity<SystemCancelOrderResponse> adminCancel(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String orderId,
            @RequestBody @Valid SystemCancelOrderRequest request
    ) {
        return ResponseEntity.ok(orderLifecycleService.adminCancel(orderId, request, idempotencyKey));
    }

    /**
     * Cancels an order on behalf of automated internal system flows.
     *
     * Valid only before delivery is in progress: PENDING, CONFIRMED,
     * AWAITING_CUSTOMER_RESPONSE, SCHEDULED, or READY_FOR_PICKUP.
     *
     * Requires Idempotency-Key.
     */
    @PostMapping("/{orderId}/system-cancel")
    public ResponseEntity<SystemCancelOrderResponse> systemCancel(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String orderId,
            @RequestBody @Valid SystemCancelOrderRequest request
    ) {
        return ResponseEntity.ok(orderLifecycleService.systemCancel(orderId, request, idempotencyKey));
    }

    /**
     * Manually activates a scheduled order into the live queue as PENDING.
     *
     * This endpoint is for admin/support manual activation. The automatic
     * scheduled-order activation job is a separate workflow.
     *
     * Requires Idempotency-Key.
     */
    @PostMapping("/{orderId}/activate-scheduled")
    public ResponseEntity<ActivateScheduledOrderResponse> activateScheduled(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(orderLifecycleService.activateScheduled(orderId, idempotencyKey));
    }

    /**
     * Cancels the active proposal without cancelling the order.
     *
     * Pending implementation.
     */
    @PostMapping("/{orderId}/cancel-active-proposal")
    public ResponseEntity<CancelActiveProposalResponse> cancelActiveProposal(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(orderLifecycleService.cancelActiveProposal(orderId, idempotencyKey));
    }

    /**
     * Cancels the active proposal and transitions the order to CANCELLED.
     *
     * Valid only when the order is in AWAITING_CUSTOMER_RESPONSE status.
     * Cancellation reason is fixed to AWAITING_CUSTOMER_RESPONSE_TIMEOUT.
     *
     * Requires Idempotency-Key.
     */
    @PostMapping("/{orderId}/cancel-proposal-and-order")
    public ResponseEntity<CancelProposalResponse> cancelProposalAndOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(orderLifecycleService.cancelProposalAndOrder(orderId, idempotencyKey));
    }
}
