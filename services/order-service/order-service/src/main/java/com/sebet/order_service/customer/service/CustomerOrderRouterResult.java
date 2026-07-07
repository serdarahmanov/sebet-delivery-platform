package com.sebet.order_service.customer.service;

import com.sebet.order_service.customer.dto.response.DeliveredOrderDetailResponse;

/**
 * Discriminated result for GET /api/v1/orders/{orderId}.
 *
 * The smart router resolves the current order status and returns either:
 *   - Delivered : the full delivered-order detail (200 OK)
 *   - Redirect  : a Location path the client should follow (302 Found)
 *
 * Keeping the HTTP decision in the controller and the routing logic in the
 * service means the service stays framework-agnostic.
 */
public sealed interface CustomerOrderRouterResult
        permits CustomerOrderRouterResult.Delivered, CustomerOrderRouterResult.Redirect {

    record Delivered(DeliveredOrderDetailResponse response) implements CustomerOrderRouterResult {}

    record Redirect(String location) implements CustomerOrderRouterResult {}
}
