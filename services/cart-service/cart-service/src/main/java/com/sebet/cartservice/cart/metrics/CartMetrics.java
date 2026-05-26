package com.sebet.cartservice.cart.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class CartMetrics {

    private final MeterRegistry registry;

    private final Counter cacheHit;
    private final Counter cacheMiss;

    public CartMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.cacheHit  = Counter.builder("cart.response.cache.hit").register(registry);
        this.cacheMiss = Counter.builder("cart.response.cache.miss").register(registry);
    }

    // ── Item operations ──────────────────────────────────────────────────────

    public void recordItemAdded(String storeId) {
        registry.counter("cart.items.added", "store_id", storeId).increment();
    }

    /** storeId of the basket the removed item belonged to */
    public void recordItemRemoved(String storeId) {
        registry.counter("cart.items.removed", "store_id", storeId).increment();
    }

    public void recordBasketCleared(String storeId) {
        registry.counter("cart.basket.cleared", "store_id", storeId).increment();
    }

    /** Fired once per store bucket touched in a batch-upsert call */
    public void recordItemsBatchUpserted(String storeId) {
        registry.counter("cart.items.batch_upserted", "store_id", storeId).increment();
    }

    // ── Promo codes ───────────────────────────────────────────────────────────

    /** result: "success" | "invalid" */
    public void recordPromoCodeClaimed(String storeId, String result) {
        registry.counter("cart.promo.claimed", "store_id", storeId, "result", result).increment();
    }

    // ── Checkout ──────────────────────────────────────────────────────────────

    /** No RedisCart exists for the user when checkout initiation is called */
    public void recordCheckoutInitiateCartNotFound() {
        registry.counter("cart.checkout.initiate.cart_not_found").increment();
    }

    /**
     * basketId prefix does not match cartId, or no basket exists for the
     * resolved storeId — fired before validation even starts.
     */
    public void recordCheckoutInitiateBasketNotFound() {
        registry.counter("cart.checkout.initiate.basket_not_found").increment();
    }

    /** Basket exists in the cart but contains no items */
    public void recordCheckoutInitiateBasketEmpty() {
        registry.counter("cart.checkout.initiate.basket_empty").increment();
    }

    /** Initiation passed all checks — READY response returned to client */
    public void recordCheckoutInitiated(String storeId) {
        registry.counter("cart.checkout.initiated", "store_id", storeId).increment();
    }

    /**
     * Initiation was blocked before the summary screen could be shown.
     * scope: "CART" | "STORE" | "STORE_BASKET" | "ITEM"
     */
    public void recordCheckoutInitiateBlocked(String storeId, String scope) {
        registry.counter("cart.checkout.initiate_blocked", "store_id", storeId, "scope", scope).increment();
    }

    public void recordCheckoutKafkaPublishFailed(String storeId) {
        registry.counter("cart.checkout.kafka.publish_failed", "store_id", storeId).increment();
    }

    /**
     * Fired when either the delivery-availability future or the validation future
     * threw during the parallel confirm-checkout phase. Covers DB down, delivery service
     * down, or any other unexpected runtime failure in that block.
     * Not fired for InterruptedException — that signals JVM/thread-pool shutdown.
     */
    public void recordCheckoutConfirmExecutionError(String storeId) {
        registry.counter("cart.checkout.confirm.execution_error", "store_id", storeId).increment();
    }

    /**
     * Fired when either the delivery-availability future or the validation future
     * threw during the parallel initiate-checkout phase. Covers DB down, delivery service
     * down, or any other unexpected runtime failure in that block.
     * Not fired for InterruptedException — that signals JVM/thread-pool shutdown.
     */
    public void recordCheckoutInitiateValidationExecutionError(String storeId) {
        registry.counter("cart.checkout.initiate.execution_error", "store_id", storeId).increment();
    }

    /**
     * Fired when the checkout executor rejects a task during confirmCheckout() because
     * all threads are busy and the queue (capacity 100) is full.
     * Distinct from execution errors — this signals thread-pool saturation, not a
     * downstream failure.
     */
    public void recordCheckoutConfirmExecutorRejected(String storeId) {
        registry.counter("cart.checkout.confirm.executor_rejected", "store_id", storeId).increment();
    }

    /**
     * Fired when the checkout executor rejects a task during initiateCheckout() because
     * all threads are busy and the queue (capacity 100) is full.
     * Distinct from execution errors — this signals thread-pool saturation, not a
     * downstream failure.
     */
    public void recordCheckoutInitiateExecutorRejected(String storeId) {
        registry.counter("cart.checkout.initiate.executor_rejected", "store_id", storeId).increment();
    }

    // ── Checkout initiation duration ──────────────────────────────────────────

    /**
     * Start a wall-clock sample for the full initiateCheckout round-trip.
     * Call {@link #stopCheckoutInitiateTimer} in a finally block to record it.
     */
    public Timer.Sample startCheckoutInitiateTimer() {
        return Timer.start(registry);
    }

    /**
     * Stop and record the initiateCheckout wall-clock sample.
     * Covers every exit path: early 404s, blocked, READY, and unexpected exceptions.
     */
    public void stopCheckoutInitiateTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("cart.checkout.initiate.duration")
                .publishPercentileHistogram()
                .register(registry));
    }

    public void recordCheckoutConfirmed(String storeId) {
        registry.counter("cart.checkout.confirmed", "store_id", storeId).increment();
    }

    // ── Checkout confirm duration ──────────────────────────────────────────────

    /**
     * Start a wall-clock sample for the full confirmCheckout round-trip.
     * Call {@link #stopCheckoutConfirmTimer} in a finally block to record it.
     */
    public Timer.Sample startCheckoutConfirmTimer() {
        return Timer.start(registry);
    }

    /**
     * Stop and record the confirmCheckout wall-clock sample.
     * Covers every exit path: 404s, rejected, confirmed, and unexpected exceptions.
     */
    public void stopCheckoutConfirmTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("cart.checkout.confirm.duration")
                .publishPercentileHistogram()
                .register(registry));
    }

    /** scope: "CART" | "STORE" | "STORE_BASKET" | "ITEM" */
    public void recordCheckoutRejected(String storeId, String scope) {
        registry.counter("cart.checkout.rejected", "store_id", storeId, "scope", scope).increment();
    }

    // ── Response cache ────────────────────────────────────────────────────────

    public void recordCacheHit() {
        cacheHit.increment();
    }

    public void recordCacheMiss() {
        cacheMiss.increment();
    }

    // ── Validation timer ──────────────────────────────────────────────────────

    public Timer.Sample startValidationTimer() {
        return Timer.start(registry);
    }

    public void stopValidationTimer(Timer.Sample sample, boolean hadBlockingIssues) {
        sample.stop(Timer.builder("cart.validation.duration")
                .tag("blocking_issues", String.valueOf(hadBlockingIssues))
                .publishPercentileHistogram()
                .register(registry));
    }

    // ── Promotion service ─────────────────────────────────────────────────────

    /**
     * Fired once per cart validation that returned a degraded promotion response.
     * @param reason "TIMEOUT" | "CIRCUIT_OPEN" | "ERROR"
     */
    public void recordPromotionServiceDegraded(String reason) {
        registry.counter("cart.promotion.degraded", "reason", reason).increment();
    }

    public Timer.Sample startPromotionCallTimer() {
        return Timer.start(registry);
    }

    /**
     * @param outcome "success" | "timeout" | "circuit_open" | "error" | "null_response"
     */
    public void stopPromotionCallTimer(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder("cart.promotion.call.duration")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry));
    }

    // ── Get basket ────────────────────────────────────────────────────────────

    /** No RedisCart exists for the user when GET store-basket is called */
    public void recordGetBasketCartNotFound() {
        registry.counter("cart.get_basket.cart_not_found").increment();
    }

    /** basketId does not match any basket in the user's cart */
    public void recordGetBasketNotFound() {
        registry.counter("cart.get_basket.basket_not_found").increment();
    }

    /** Basket exists but contains no items */
    public void recordGetBasketEmpty() {
        registry.counter("cart.get_basket.empty").increment();
    }

    // ── Delivery service calls ────────────────────────────────────────────────

    public Timer.Sample startDeliveryCallTimer() {
        return Timer.start(registry);
    }

    /**
     * @param outcome   "success" | "null_response" | "timeout" | "circuit_open" | "error"
     * @param operation "availability" | "fee_quote" | "scheduled_quote"
     */
    public void stopDeliveryCallTimer(Timer.Sample sample, String outcome, String operation) {
        sample.stop(Timer.builder("cart.delivery.call.duration")
                .tag("outcome", outcome)
                .tag("operation", operation)
                .publishPercentileHistogram()
                .register(registry));
    }

    /**
     * @param outcome   "null_response" | "timeout" | "circuit_open" | "error"
     * @param operation "availability" | "fee_quote" | "scheduled_quote"
     */
    public void recordDeliveryServiceDegraded(String outcome, String operation) {
        registry.counter("cart.delivery.degraded", "outcome", outcome, "operation", operation).increment();
    }

    // ── Delivery fee quotes ───────────────────────────────────────────────────

    /** Quote was still valid in Redis — no remote call needed */
    public void recordDeliveryQuoteCacheHit(String storeId) {
        registry.counter("cart.delivery.quote.cache_hit", "store_id", storeId).increment();
    }

    /**
     * Remote quote fetched and accepted.
     * @param type "asap" | "scheduled"
     */
    public void recordDeliveryQuoteFetched(String storeId, String type) {
        registry.counter("cart.delivery.quote.fetched",
                "store_id", storeId, "type", type).increment();
    }

    /**
     * Remote quote fetch failed or was rejected.
     * @param type "asap" | "scheduled"
     */
    public void recordDeliveryQuoteFetchFailed(String storeId, String type) {
        registry.counter("cart.delivery.quote.fetch_failed",
                "store_id", storeId, "type", type).increment();
    }
}
