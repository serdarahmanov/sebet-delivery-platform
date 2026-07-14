package com.sebet.order_service.customer.controller;

import com.sebet.order_service.customer.dto.request.RespondToOrderChangesRequest;
import com.sebet.order_service.customer.dto.request.UpdateScheduledOrderRequest;
import com.sebet.order_service.customer.dto.response.ActivateScheduledNowResponse;
import com.sebet.order_service.customer.dto.response.*;
import com.sebet.order_service.customer.service.CustomerOrderLifecycleService;
import com.sebet.order_service.customer.service.CustomerOrderQueryService;
import com.sebet.order_service.customer.service.CustomerOrderRouterResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Customer-facing order endpoints.
 *
 * All endpoints require a valid {@code X-User-Id} header, enforced globally
 * by {@link com.sebet.order_service.config.UserIdInterceptor}.
 *
 * Endpoint map:
 *
 *   GET  /api/v1/orders                      → paginated history feed (DB)
 *   GET  /api/v1/orders/active               → active order cards    (C1 → C2)
 *   GET  /api/v1/orders/active/{orderId}     → tracking screen mount (C2 + C4)
 *   GET   /api/v1/orders/scheduled/{orderId}  → scheduled detail      (DB)
 *   PATCH /api/v1/orders/scheduled/{orderId}  → modify scheduled order (DB)
 *   GET  /api/v1/orders/cancelled/{orderId}  → cancellation receipt  (DB)
 *   GET  /api/v1/orders/{orderId}            → smart router: 200 if DELIVERED,
 *                                              302 → /active/{orderId}    if ACTIVE,
 *                                              302 → /cancelled/{orderId} if CANCELLED,
 *                                              302 → /scheduled/{orderId} if SCHEDULED
 *   GET  /api/v1/orders/{orderId}/status              → lightweight status     (C4)
 *   GET  /api/v1/orders/{orderId}/tracking            → live GPS + ETA poll    (C3 + C4)
 *   GET  /api/v1/orders/{orderId}/verification-code   → delivery code fallback (C7)
 *   GET  /api/v1/orders/{orderId}/proposed-changes    → fetch active store proposal (C8)
 *   POST /api/v1/orders/{orderId}/respond-to-changes  → respond to store proposal
 *   POST /api/v1/orders/{orderId}/cancel              → cancel order
 *
 * ── Pending (not yet implemented) ───────────────────────────────────────────
 *   Kafka consumer : order.arrived event → generates code, writes C7, pushes WS
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class CustomerOrderController {

    private final CustomerOrderQueryService queryService;
    private final CustomerOrderLifecycleService lifecycleService;

    // ── History ──────────────────────────────────────────────────────────────

    /**
     * Paginated order history feed — DELIVERED, CANCELLED, and SCHEDULED orders only.
     * Active orders are intentionally excluded; use GET /api/v1/orders/active for those.
     * Each row carries an {@link OrderHistoryItemResponse.OrderDetailRoute} discriminator
     * so the frontend knows which detail endpoint to call and which card to render.
     */
    @GetMapping
    public ResponseEntity<Page<OrderHistoryItemResponse>> getOrderHistory(
            @RequestHeader("X-User-Id") String userId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(queryService.getOrderHistory(userId, pageable));
    }

    // ── Active ───────────────────────────────────────────────────────────────

    /**
     * Returns all currently active orders for the user as static snapshots.
     * Live fields (status, ETA, GPS, rider) are pushed via WebSocket
     * (/topic/orders/{orderId}/tracking) — subscribe per orderId after this call.
     */
    @GetMapping("/active")
    public ResponseEntity<List<ActiveOrderItemResponse>> getActiveOrders(
            @RequestHeader("X-User-Id") String userId
    ) {
        return ResponseEntity.ok(queryService.getActiveOrders(userId));
    }

    /**
     * Tracking screen initial mount payload.
     * Includes the static order snapshot (C2) and the current status (C4).
     * WebSocket delivers all subsequent live updates.
     */
    @GetMapping("/active/{orderId}")
    public ResponseEntity<ActiveOrderDetailResponse> getActiveOrderDetail(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(queryService.getActiveOrderDetail(userId, orderId));
    }

    // ── Scheduled ────────────────────────────────────────────────────────────

    /** Detail view for a scheduled future order, including cancellation eligibility. */
    @GetMapping("/scheduled/{orderId}")
    public ResponseEntity<ScheduledOrderDetailResponse> getScheduledOrderDetail(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(queryService.getScheduledOrderDetail(userId, orderId));
    }

    /**
     * Partially updates a scheduled order before its modification cutoff.
     *
     * Updatable fields: {@code scheduledFor} (new delivery time),
     * {@code addressId} (new drop-off address). At least one must be provided.
     *
     * Returns 409 Conflict when the order is outside the modification window
     * ({@code canCancel == false}) or has already transitioned out of SCHEDULED status.
     * Returns the full updated {@link ScheduledOrderDetailResponse} on success.
     */
    @PatchMapping("/scheduled/{orderId}")
    public ResponseEntity<ScheduledOrderDetailResponse> updateScheduledOrder(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String orderId,
            @RequestBody @Valid UpdateScheduledOrderRequest request
    ) {
        return ResponseEntity.ok(lifecycleService.updateScheduledOrder(userId, orderId, request));
    }

    // ── Cancelled ────────────────────────────────────────────────────────────

    /** Cancellation receipt — shows what was ordered, why it was cancelled, and refund state. */
    @GetMapping("/cancelled/{orderId}")
    public ResponseEntity<CancelledOrderDetailResponse> getCancelledOrderDetail(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(queryService.getCancelledOrderDetail(userId, orderId));
    }

    // ── Delivered ────────────────────────────────────────────────────────────

    /**
     * Smart order router — the single canonical entry point for any order ID.
     *
     * Behaviour by order status:
     *   DELIVERED                        → 200  DeliveredOrderDetailResponse
     *   ACTIVE (any in-progress status)  → 302  /api/v1/orders/active/{orderId}
     *   CANCELLED                        → 302  /api/v1/orders/cancelled/{orderId}
     *   SCHEDULED                        → 302  /api/v1/orders/scheduled/{orderId}
     *   not found / wrong user           → 404
     *
     * The redirect lets the client bookmark or share a plain order URL without
     * knowing the current status — the server resolves it at request time.
     * 302 (not 301) is used because status changes over the order lifetime.
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<?> getOrderDetail(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String orderId
    ) {
        CustomerOrderRouterResult result = queryService.routeOrderDetail(userId, orderId);
        if (result instanceof CustomerOrderRouterResult.Delivered d) {
            return ResponseEntity.ok(d.response());
        }
        CustomerOrderRouterResult.Redirect r = (CustomerOrderRouterResult.Redirect) result;
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, r.location())
                .build();
    }

    // ── Status & Tracking ────────────────────────────────────────────────────

    /**
     * Lightweight status poll — cheap C4 read.
     * Use for kitchen screens or push-notification triggers that need
     * only the status string without loading the full order payload.
     */
    @GetMapping("/{orderId}/status")
    public ResponseEntity<OrderStatusResponse> getOrderStatus(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(queryService.getOrderStatus(userId, orderId));
    }

    /**
     * Live GPS + ETA poll (C3 + C4).
     * Fallback for clients that cannot maintain a WebSocket connection.
     * Prefer WebSocket (/topic/orders/{orderId}/tracking) where possible.
     */
    @GetMapping("/{orderId}/tracking")
    public ResponseEntity<OrderTrackingResponse> getOrderTracking(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(queryService.getOrderTracking(userId, orderId));
    }

    // ── Verification code ────────────────────────────────────────────────────

    /**
     * HTTP fallback for the delivery verification code.
     *
     * The code is normally delivered via WebSocket when the driver marks
     * arrival (status → ARRIVED). Use this endpoint when the customer's app
     * was offline during that transition and missed the push.
     *
     * Source  : Cache 7 (order:verification:{orderId})
     * Returns : 200 + VerificationCodeResponse  when the code exists in cache
     *           404                              when code not yet generated or TTL expired (30 min)
     *
     * TODO: code is written to C7 by the OrderArrivedEventConsumer — implement with Kafka layer.
     */
    @GetMapping("/{orderId}/verification-code")
    public ResponseEntity<VerificationCodeResponse> getVerificationCode(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(queryService.getVerificationCode(userId, orderId));
    }

    // ── Proposed changes ─────────────────────────────────────────────────────

    /**
     * Returns the active change proposal submitted by the store.
     *
     * Used when the customer's app was offline during the push notification and
     * needs to load the proposal on re-launch.
     *
     * Source  : Cache 8 (order:proposals:{orderId})
     * Returns : 200 + OrderProposedChangesResponse  when a proposal is active
     *           404                                 when no proposal exists or has expired
     */
    @GetMapping("/{orderId}/proposed-changes")
    public ResponseEntity<OrderProposedChangesResponse> getProposedChanges(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(queryService.getProposedChanges(userId, orderId));
    }

    /**
     * Submits the customer's response to the store's change proposal.
     *
     * Possible outcomes:
     *   AWAITING_CUSTOMER_RESPONSE — customer accepted (ACCEPT_ALL or ACCEPT_WITH_MODIFICATIONS);
     *                                OrderProposalAccepted event published; order stays in this
     *                                status until the promo service recalculates and calls back via
     *                                POST /api/v1/internal/orders/{orderId}/update-after-proposal.
     *   CANCELLED                  — customer chose CANCEL_ORDER; order cancelled immediately.
     *
     * Clears Cache 8 (proposals) on completion regardless of outcome.
     *
     * Returns 400 when globalDecision is ACCEPT_WITH_MODIFICATIONS and itemDecisions is empty.
     * Returns 404 when no active proposal exists or the order does not belong to the caller.
     * Returns 409 when the order is not in AWAITING_CUSTOMER_RESPONSE status.
     */
    @PostMapping("/{orderId}/respond-to-changes")
    public ResponseEntity<RespondToOrderChangesResponse> respondToOrderChanges(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String orderId,
            @RequestBody @Valid RespondToOrderChangesRequest request
    ) {
        return ResponseEntity.ok(lifecycleService.respondToChanges(userId, orderId, request));
    }

    // ── Activate scheduled now ───────────────────────────────────────────────

    /**
     * Customer requests their scheduled order to be dispatched immediately.
     *
     * Transition : SCHEDULED → PENDING
     * Actor      : USER (customer-initiated, distinct from the T-30min background job)
     *
     * Returns 404 if the order does not exist or does not belong to the caller.
     * Returns 409 if the order is not in SCHEDULED status.
     */
    @PostMapping("/scheduled/{orderId}/activate-now")
    public ResponseEntity<ActivateScheduledNowResponse> activateScheduledNow(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(lifecycleService.activateNow(userId, orderId));
    }

    // ── Cancel ───────────────────────────────────────────────────────────────

    /**
     * Cancels an order.
     *
     * Succeeds when status is:
     *   PENDING or CONFIRMED  — active orders not yet picked up
     *   SCHEDULED             — future orders within the cancellation window (canCancel == true)
     *
     * Clears Cache 1, 2, 3, and 4 on success.
     * Returns 409 Conflict if the order has progressed past CONFIRMED or the
     * scheduled cancellation window has closed.
     */
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<CancelOrderResponse> cancelOrder(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(lifecycleService.cancelOrder(userId, orderId));
    }
}
