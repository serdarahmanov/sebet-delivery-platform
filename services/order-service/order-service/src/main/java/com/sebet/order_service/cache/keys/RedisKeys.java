package com.sebet.order_service.cache.keys;

/**
 * Central registry of all Redis key patterns used by the order-service.
 * All key construction goes through static factory methods so key shapes
 * are never duplicated across repositories.
 */
public final class RedisKeys {

    private RedisKeys() {}

    // ── raw prefixes ────────────────────────────────────────────────────────
    private static final String ACTIVE_ORDERS_PREFIX = "user:active_orders:";
    private static final String ORDER_PREFIX         = "order:";
    private static final String TRACKING_PREFIX      = "order:tracking:";
    private static final String STATUS_PREFIX        = "order:status:";
    private static final String LOCK_PREFIX          = "order:lock:";
    private static final String TIMELINE_PREFIX      = "order:timeline:";
    private static final String VERIFICATION_PREFIX  = "order:verification:";

    // ── Cache 1: user:active_orders:{userId}  (Redis SET) ───────────────────
    public static String activeOrders(String userId) {
        return ACTIVE_ORDERS_PREFIX + userId;
    }

    // ── Cache 2: order:{orderId}  (STRING / JSON) ────────────────────────────
    public static String order(String orderId) {
        return ORDER_PREFIX + orderId;
    }

    // ── Cache 3: order:tracking:{orderId}  (STRING / JSON) ──────────────────
    public static String orderTracking(String orderId) {
        return TRACKING_PREFIX + orderId;
    }

    // ── Cache 4: order:status:{orderId}  (STRING plain) ─────────────────────
    public static String orderStatus(String orderId) {
        return STATUS_PREFIX + orderId;
    }

    // ── Cache 5: order:lock:{cartId}  (STRING / SETNX) ──────────────────────
    public static String orderLock(String cartId) {
        return LOCK_PREFIX + cartId;
    }

    // ── Cache 6: order:timeline:{orderId}  (LIST / JSON entries) ────────────
    public static String orderTimeline(String orderId) {
        return TIMELINE_PREFIX + orderId;
    }

    // ── Cache 7: order:verification:{orderId}  (STRING / JSON) ──────────────
    public static String orderVerification(String orderId) {
        return VERIFICATION_PREFIX + orderId;
    }
}
