package com.sebet.order_service.store.controller;

import com.sebet.order_service.store.dto.request.ProposeOrderChangesRequest;
import com.sebet.order_service.store.dto.request.RejectOrderRequest;
import com.sebet.order_service.store.dto.request.StoreCancelOrderRequest;
import com.sebet.order_service.store.dto.response.*;
import com.sebet.order_service.store.service.StoreOrderLifecycleService;
import com.sebet.order_service.store.service.StoreOrderQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Store-facing order endpoints.
 *
 * All endpoints require a valid {@code X-Store-Id} header, enforced globally
 * by {@link com.sebet.order_service.config.StoreIdInterceptor}.
 *
 * Every write endpoint performs an ownership check — the storeId embedded in
 * the order snapshot (Cache 2 / DB) must match the {@code X-Store-Id} header;
 * a mismatch returns 404 Not Found, matching the customer API's ownership-hiding
 * behavior.
 *
 * Endpoint map:
 *
 *   GET  /api/v1/store/orders                           → paginated history (DELIVERED + CANCELLED)
 *   GET  /api/v1/store/orders/active                    → kitchen dashboard: all live orders
 *   GET  /api/v1/store/orders/scheduled                 → upcoming scheduled orders (before active window)
 *   GET  /api/v1/store/orders/{orderId}                 → full order detail (any status)
 *   GET  /api/v1/store/orders/{orderId}/status          → lightweight status poll
 *
 *   POST /api/v1/store/orders/{orderId}/accept          → PENDING                                    → CONFIRMED
 *   POST /api/v1/store/orders/{orderId}/reject          → PENDING                                    → CANCELLED
 *   POST /api/v1/store/orders/{orderId}/ready           → CONFIRMED                                  → READY_FOR_PICKUP
 *   POST /api/v1/store/orders/{orderId}/propose-changes → CONFIRMED                                  → AWAITING_CUSTOMER_RESPONSE
 *   POST /api/v1/store/orders/{orderId}/cancel          → CONFIRMED | AWAITING_CUSTOMER_RESPONSE     → CANCELLED
 *
 * ── Pending (not yet implemented) ───────────────────────────────────────────
 *   WebSocket push for live status transitions on the kitchen dashboard.
 *   Store response timeout job (PENDING order ignored by the store).
 *   (Customer response timeout is already automated by ProposalTimeoutScheduler,
 *   which cancels AWAITING_CUSTOMER_RESPONSE orders with reason
 *   AWAITING_CUSTOMER_RESPONSE_TIMEOUT.)
 */
@RestController
@RequestMapping("/api/v1/store/orders")
@RequiredArgsConstructor
public class StoreOrderController {

    private final StoreOrderLifecycleService storeOrderLifecycleService;
    private final StoreOrderQueryService storeOrderQueryService;

    // ── History ──────────────────────────────────────────────────────────────

    /**
     * Paginated order history feed — DELIVERED and CANCELLED orders only.
     *
     * Active orders are intentionally excluded; use GET /active for those.
     * Each row carries a {@link StoreOrderHistoryItemResponse.OrderDetailRoute}
     * discriminator so the frontend knows which card layout to render.
     *
     * Source   : DB
     * Returns  : Page&lt;StoreOrderHistoryItemResponse&gt;
     */
    @GetMapping
    public ResponseEntity<Page<StoreOrderHistoryItemResponse>> getOrderHistory(
            @RequestHeader("X-Store-Id") String storeId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(storeOrderQueryService.getOrderHistory(storeId, pageable));
    }

    // ── Active ───────────────────────────────────────────────────────────────

    /**
     * Returns all currently active orders for the store as kitchen-dashboard cards.
     *
     * Orders are sourced from Cache 1b (store active order ID set) and enriched
     * with Cache 2 (item snapshot) and Cache 4 (current status).
     *
     * Scheduled orders appear here only 30 minutes before their requested delivery
     * time; before that window they are served by GET /scheduled.
     *
     * Live status changes are pushed via WebSocket after this list is loaded.
     *
     * Source   : Cache 1b → Cache 2 + Cache 4
     * Returns  : List&lt;StoreActiveOrderItemResponse&gt;
     */
    @GetMapping("/active")
    public ResponseEntity<List<StoreActiveOrderItemResponse>> getActiveOrders(
            @RequestHeader("X-Store-Id") String storeId
    ) {
        return ResponseEntity.ok(storeOrderQueryService.getActiveOrders(storeId));
    }

    // ── Scheduled ────────────────────────────────────────────────────────────

    /**
     * Returns all upcoming scheduled orders that have not yet entered the active
     * window, sorted ascending by scheduled delivery time (soonest first).
     *
     * An order appears here from the moment it is placed until 30 minutes before
     * its {@code scheduledFor} time, at which point the transition job moves it
     * to PENDING and it enters the active queue.
     *
     * Intended for stock planning — store staff can review what items will be
     * needed throughout the day before the orders become actionable.
     *
     * Source   : Cache 1c (store:scheduled_orders:{storeId} ZSET) → Cache 2
     * Returns  : List&lt;StoreScheduledOrderItemResponse&gt; sorted by scheduledFor asc
     */
    @GetMapping("/scheduled")
    public ResponseEntity<List<StoreScheduledOrderItemResponse>> getScheduledOrders(
            @RequestHeader("X-Store-Id") String storeId
    ) {
        return ResponseEntity.ok(storeOrderQueryService.getScheduledOrders(storeId));
    }

    // ── Detail ───────────────────────────────────────────────────────────────

    /**
     * Full order detail — works for any order status.
     *
     * Source strategy:
     *   Order detail and timeline → DB
     *   Pending proposal          → Cache 8 when status is AWAITING_CUSTOMER_RESPONSE
     *
     * Returns 404 if the order does not exist or does not belong to this store.
     *
     * Source   : DB + Cache 8 when applicable
     * Returns  : StoreOrderDetailResponse
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<StoreOrderDetailResponse> getOrderDetail(
            @RequestHeader("X-Store-Id") String storeId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(storeOrderQueryService.getOrderDetail(storeId, orderId));
    }

    // ── Status ───────────────────────────────────────────────────────────────

    /**
     * Lightweight status poll — Cache 4 status/ownership read with DB fallback.
     *
     * Use for kitchen display screens that only need the current status string
     * without loading the full order payload.
     *
     * Returns 404 if the order does not exist or does not belong to this store.
     *
     * Source   : Cache 4 | DB
     * Returns  : StoreOrderStatusResponse
     */
    @GetMapping("/{orderId}/status")
    public ResponseEntity<StoreOrderStatusResponse> getOrderStatus(
            @RequestHeader("X-Store-Id") String storeId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(storeOrderQueryService.getOrderStatus(storeId, orderId));
    }

    // ── Accept ───────────────────────────────────────────────────────────────

    /**
     * Store accepts a PENDING order and begins preparation.
     *
     * Transition : PENDING → CONFIRMED
     * Side effects: updates Cache 4 (status).
     *
     * Returns 409 Conflict  if the order is not in PENDING status.
     * Returns 404 Not Found if the order does not belong to this store.
     */
    @PostMapping("/{orderId}/accept")
    public ResponseEntity<StoreAcceptOrderResponse> acceptOrder(
            @RequestHeader("X-Store-Id") String storeId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(storeOrderLifecycleService.acceptOrder(storeId, orderId));
    }

    // ── Reject ───────────────────────────────────────────────────────────────

    /**
     * Store rejects a PENDING order before accepting it.
     *
     * Transition : PENDING → CANCELLED
     * Side effects: clears Cache 1 (user active orders), Cache 1b (store active orders),
     *               Cache 2 (order snapshot), Cache 4 (status).
     *
     * When {@code reason == OUT_OF_STOCK}, {@code outOfStockItems} must be provided
     * so the notification service can show the customer exactly what was unavailable.
     *
     * If the store has already accepted and discovers stock issues during preparation,
     * use POST /{orderId}/propose-changes instead.
     *
     * Returns 409 Conflict  if the order is not in PENDING status.
     * Returns 404 Not Found if the order does not belong to this store.
     */
    @PostMapping("/{orderId}/reject")
    public ResponseEntity<StoreRejectOrderResponse> rejectOrder(
            @RequestHeader("X-Store-Id") String storeId,
            @PathVariable String orderId,
            @RequestBody @Valid RejectOrderRequest request
    ) {
        return ResponseEntity.ok(storeOrderLifecycleService.rejectOrder(storeId, orderId, request));
    }

    // ── Ready ────────────────────────────────────────────────────────────────

    /**
     * Store marks a CONFIRMED order as packed and ready for courier pickup.
     *
     * Transition : CONFIRMED → READY_FOR_PICKUP
     * Side effects: updates Cache 4 (status), writes PACKED timestamp to Cache 6 (timeline).
     *               Should trigger driver-dispatch logic — see Gap 3, not yet implemented.
     *
     * Returns 409 Conflict  if the order is not in CONFIRMED status.
     * Returns 404 Not Found if the order does not belong to this store.
     */
    @PostMapping("/{orderId}/ready")
    public ResponseEntity<StoreReadyOrderResponse> markOrderReady(
            @RequestHeader("X-Store-Id") String storeId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(storeOrderLifecycleService.markOrderReady(storeId, orderId));
    }

    // ── Cancel (post-acceptance) ─────────────────────────────────────────────

    /**
     * Store cancels an order it has already accepted.
     *
     * Valid pre-conditions : {@code CONFIRMED} or {@code AWAITING_CUSTOMER_RESPONSE}.
     * Transition           : → CANCELLED
     * Side effects         : atomically removes C1/C1b memberships and clears C2, C3, C4,
     *                        and C6 through the cache-eviction fallback pattern.
     *
     * Semantically distinct from POST /reject, which is a pre-acceptance refusal on a
     * PENDING order.  Use this endpoint when the store has already committed to the order
     * and must back out (e.g. unexpected closure, equipment failure).
     *
     * {@code reason} is restricted to post-acceptance values ({@code STORE_CLOSED},
     * {@code STORE_UNABLE_TO_FULFIL}) — pre-acceptance reasons are rejected at the
     * service layer.
     *
     * Requires Idempotency-Key.
     * Returns 409 Conflict  if the order is not in CONFIRMED or AWAITING_CUSTOMER_RESPONSE status.
     * Returns 409 Conflict  if the Idempotency-Key was reused with a different request body.
     * Returns 404 Not Found if the order does not belong to this store.
     */
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<StoreCancelOrderResponse> cancelOrder(
            @RequestHeader("X-Store-Id") String storeId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String orderId,
            @RequestBody @Valid StoreCancelOrderRequest request
    ) {
        return ResponseEntity.ok(storeOrderLifecycleService.cancelOrder(storeId, orderId, request, idempotencyKey));
    }

    // ── Propose changes ──────────────────────────────────────────────────────

    /**
     * Store proposes alternative quantities for items discovered to be understocked
     * during preparation of a CONFIRMED order.
     *
     * Transition : CONFIRMED → AWAITING_CUSTOMER_RESPONSE
     * Side effects: stores the proposal, updates Cache 4 (status), dispatches a push
     *               notification to the customer with per-item choices:
     *                 1. Accept the proposed (reduced) quantity.
     *                 2. Remove the item and continue with the rest of the order.
     *                 3. Cancel the order entirely.
     *
     * If the customer does not respond within the allowed window the order is
     * automatically cancelled with reason {@code AWAITING_CUSTOMER_RESPONSE_TIMEOUT}.
     *
     * Items completely out of stock must have {@code availableQuantity == null}.
     * Items with partial stock must have {@code availableQuantity > 0 && < requestedQuantity}.
     *
     * Returns 409 Conflict  if the order is not in CONFIRMED status.
     * Returns 404 Not Found if the order does not belong to this store.
     */
    @PostMapping("/{orderId}/propose-changes")
    public ResponseEntity<StoreProposeOrderChangesResponse> proposeOrderChanges(
            @RequestHeader("X-Store-Id") String storeId,
            @PathVariable String orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid ProposeOrderChangesRequest request
    ) {
        return ResponseEntity.ok(storeOrderLifecycleService.proposeChanges(storeId, orderId, request, idempotencyKey));
    }

    /**
     * Store cancels its active proposal without cancelling the order.
     *
     * Intended for cases where the store needs to withdraw the current proposal
     * and submit a corrected one.
     *
     * Requires Idempotency-Key.
     */
    @PostMapping("/{orderId}/cancel-active-proposal")
    public ResponseEntity<StoreCancelActiveProposalResponse> cancelActiveProposal(
            @RequestHeader("X-Store-Id") String storeId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(storeOrderLifecycleService.cancelActiveProposal(storeId, orderId, idempotencyKey));
    }
}
