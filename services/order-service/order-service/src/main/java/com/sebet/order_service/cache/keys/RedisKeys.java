package com.sebet.order_service.cache.keys;

/**
 * Central registry of all Redis key patterns used by the order-service.
 * All key construction goes through static factory methods so key shapes
 * are never duplicated across repositories.
 */
public final class RedisKeys {

    private RedisKeys() {}

    // ── raw prefixes ────────────────────────────────────────────────────────
    private static final String ACTIVE_ORDERS_PREFIX            = "user:active_orders:";
    private static final String STORE_ACTIVE_ORDERS_PREFIX      = "store:active_orders:";
    private static final String STORE_SCHEDULED_ORDERS_PREFIX   = "store:scheduled_orders:";
    private static final String ORDER_PREFIX               = "order:";
    private static final String TRACKING_PREFIX            = "order:tracking:";
    private static final String STATUS_PREFIX              = "order:status:";
    private static final String LOCK_PREFIX                = "order:lock:";
    private static final String TIMELINE_PREFIX            = "order:timeline:";
    private static final String VERIFICATION_PREFIX        = "order:verification:";
    private static final String PROPOSALS_PREFIX           = "order:proposals:";

    // ── Cache 1: user:active_orders:{userId}  (Redis SET) ───────────────────
    public static String activeOrders(String userId) {
        return ACTIVE_ORDERS_PREFIX + userId;
    }

    // ── Cache 1b: store:active_orders:{storeId}  (Redis SET) ────────────────
    public static String storeActiveOrders(String storeId) {
        return STORE_ACTIVE_ORDERS_PREFIX + storeId;
    }

    // ── Cache 1c: store:scheduled_orders:{storeId}  (Redis ZSET, score = scheduledFor epoch) ──
    public static String storeScheduledOrders(String storeId) {
        return STORE_SCHEDULED_ORDERS_PREFIX + storeId;
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

    // ── Cache 8: order:proposals:{orderId}  (STRING / JSON) ──────────────────
    public static String orderProposals(String orderId) {
        return PROPOSALS_PREFIX + orderId;
    }
}
