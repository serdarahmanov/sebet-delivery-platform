package com.sebet.order_service.driver.controller;

import com.sebet.order_service.driver.dto.request.DriverCompleteDeliveryRequest;
import com.sebet.order_service.driver.dto.response.DriverArriveResponse;
import com.sebet.order_service.driver.dto.response.DriverCompleteDeliveryResponse;
import com.sebet.order_service.driver.dto.response.DriverDeclineResponse;
import com.sebet.order_service.driver.dto.response.DriverOrderDetailResponse;
import com.sebet.order_service.driver.dto.response.DriverPickupResponse;
import com.sebet.order_service.driver.service.DriverOrderLifecycleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Driver-facing order endpoints.
 *
 * All endpoints require a valid {@code X-Driver-Id} header, enforced globally
 * by {@link com.sebet.order_service.config.DriverIdInterceptor}.
 *
 * Every write endpoint verifies that the {@code driverId} on the order matches
 * the {@code X-Driver-Id} header — a mismatch returns 403 Forbidden.
 * The location update endpoint is the only exception: it does not require
 * prior assignment (a driver can push location while en route to the store).
 *
 * Endpoint map:
 *
 *   GET  /api/v1/driver/orders/{orderId}           → delivery detail       (C2 + C4)
 *   POST /api/v1/driver/orders/{orderId}/pickup    → READY_FOR_PICKUP → OUT_FOR_DELIVERY
 *   POST /api/v1/driver/orders/{orderId}/arrive    → OUT_FOR_DELIVERY → ARRIVED
 *   POST /api/v1/driver/orders/{orderId}/complete  → ARRIVED → DELIVERED
 *   POST /api/v1/driver/orders/{orderId}/decline   → unassigns driver (status unchanged)
 *
 * ── Location updates ─────────────────────────────────────────────────────────
 *   GPS and ETA are NOT received here.  The driver app sends coordinates to the
 *   tracking service, which calculates ETA and publishes a DriverLocationUpdatedEvent.
 *   Order-service consumes that event, updates Cache 3, and pushes a WebSocket
 *   message to the customer.
 *
 * ── Guards ───────────────────────────────────────────────────────────────────
 *   /pickup   : order must be READY_FOR_PICKUP AND driverId non-null on the order
 *   /arrive   : order must be OUT_FOR_DELIVERY
 *   /complete : order must be ARRIVED; verificationCode must match Cache 7
 *   /decline  : status must be PENDING, CONFIRMED, or READY_FOR_PICKUP; driverId must match
 *
 * ── Side effects ─────────────────────────────────────────────────────────────
 *   /pickup   : updates Cache 4 (status); writes ON_THE_WAY step to Cache 6
 *   /arrive   : updates Cache 4; generates verification code → Cache 7 (30-min TTL);
 *               pushes code to customer via WebSocket (planned)
 *   /complete : updates Cache 4; clears Cache 1 (user active), Cache 1b (store active),
 *               Cache 2 (snapshot), Cache 3 (tracking), Cache 7 (verification)
 *   /decline  : clears driverId and driverAssignedAt on the order entity; no status change
 */
@RestController
@RequestMapping("/api/v1/driver/orders")
@RequiredArgsConstructor
public class DriverOrderController {

    private final DriverOrderLifecycleService driverOrderLifecycleService;

    // ── Detail ───────────────────────────────────────────────────────────────

    /**
     * Returns the delivery detail for the assigned order.
     *
     * Contains the store pickup location, customer drop-off address, item list
     * for bag verification, and estimated delivery time.
     *
     * Source   : Cache 2 (order snapshot) + Cache 4 (current status)
     * Returns  : DriverOrderDetailResponse
     *
     * Returns 403 Forbidden if the order is not assigned to this driver.
     * Returns 404 Not Found  if the order does not exist.
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<DriverOrderDetailResponse> getOrderDetail(
            @RequestHeader("X-Driver-Id") String driverId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(driverOrderLifecycleService.getOrderDetail(driverId, orderId));
    }

    // ── Pickup ───────────────────────────────────────────────────────────────

    /**
     * Driver confirms they have collected the order from the store.
     *
     * Transition : READY_FOR_PICKUP → OUT_FOR_DELIVERY
     * Side effects: updates Cache 4 (status); appends ON_THE_WAY step to Cache 6 (timeline).
     *
     * Guards:
     *   - Order status must be READY_FOR_PICKUP.
     *   - {@code driverId} on the order must match {@code X-Driver-Id}.
     *
     * Returns 403 Forbidden if the order is not assigned to this driver.
     * Returns 409 Conflict if the order is not in READY_FOR_PICKUP status.
     */
    @PostMapping("/{orderId}/pickup")
    public ResponseEntity<DriverPickupResponse> confirmPickup(
            @RequestHeader("X-Driver-Id") String driverId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(driverOrderLifecycleService.confirmPickup(driverId, orderId));
    }

    // ── Arrive ───────────────────────────────────────────────────────────────

    /**
     * Driver marks arrival at the customer's delivery address.
     *
     * Transition : OUT_FOR_DELIVERY → ARRIVED
     * Side effects:
     *   - Updates Cache 4 (status).
     *   - Generates a 2-digit verification code and writes it to Cache 7
     *     (order:verification:{orderId}) with a 30-minute TTL.
     *   - Pushes the code to the customer's app via WebSocket (planned).
     *
     * The verification code is NOT returned in this response — it is delivered
     * exclusively to the customer.  The customer shows it to the driver on-screen.
     *
     * Guards:
     *   - Order status must be OUT_FOR_DELIVERY.
     *   - {@code driverId} on the order must match {@code X-Driver-Id}.
     *
     * Returns 403 Forbidden if the order is not assigned to this driver.
     * Returns 409 Conflict  if the order is not in OUT_FOR_DELIVERY status.
     */
    @PostMapping("/{orderId}/arrive")
    public ResponseEntity<DriverArriveResponse> markArrived(
            @RequestHeader("X-Driver-Id") String driverId,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(driverOrderLifecycleService.markArrived(driverId, orderId));
    }

    // ── Complete ─────────────────────────────────────────────────────────────

    /**
     * Driver submits the verification code shown on the customer's screen to
     * confirm successful delivery.
     *
     * Transition : ARRIVED → DELIVERED
     * Side effects:
     *   - Marks {@code verifiedAt} in Cache 7.
     *   - Clears Cache 1 (user active orders), Cache 1b (store active orders),
     *     Cache 2 (order snapshot), Cache 3 (tracking), Cache 7 (verification).
     *   - Updates Cache 4 (status).
     *   - Sets {@code deliveredAt} on the order entity.
     *
     * Guards:
     *   - Order status must be ARRIVED.
     *   - {@code driverId} on the order must match {@code X-Driver-Id}.
     *   - {@code verificationCode} must match the code in Cache 7.
     *
     * Returns 400 Bad Request  if the verification code does not match.
     * Returns 403 Forbidden    if the order is not assigned to this driver.
     * Returns 404 Not Found    if the verification code has expired (30-min TTL).
     * Returns 409 Conflict     if the order is not in ARRIVED status.
     */
    @PostMapping("/{orderId}/complete")
    public ResponseEntity<DriverCompleteDeliveryResponse> completeDelivery(
            @RequestHeader("X-Driver-Id") String driverId,
            @PathVariable String orderId,
            @RequestBody @Valid DriverCompleteDeliveryRequest request
    ) {
        return ResponseEntity.ok(driverOrderLifecycleService.completeDelivery(driverId, orderId, request.verificationCode()));
    }

    // ── Decline ──────────────────────────────────────────────────────────────

    /**
     * Driver declines the assigned order before pickup.
     *
     * Effect: clears {@code driverId} and {@code driverAssignedAt} on the order entity.
     * The order status is NOT changed — it remains in its current state so that
     * dispatch can reassign another driver.
     *
     * Guards:
     *   - Order status must be PENDING, CONFIRMED, or READY_FOR_PICKUP (before OUT_FOR_DELIVERY).
     *   - {@code driverId} on the order must match {@code X-Driver-Id}.
     *
     * Returns 403 Forbidden if the order is not assigned to this driver.
     * Returns 409 Conflict  if the order is already OUT_FOR_DELIVERY or beyond.
     */
    @PostMapping("/{orderId}/decline")
    public ResponseEntity<DriverDeclineResponse> declineAssignment(
            @RequestHeader("X-Driver-Id") String driverId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(driverOrderLifecycleService.declineAssignment(driverId, orderId, idempotencyKey));
    }
}
