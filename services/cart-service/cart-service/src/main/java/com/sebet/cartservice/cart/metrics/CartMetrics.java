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

    // ── Checkout confirm not-found ─────────────────────────────────────────────

    /** No RedisCart exists for the user when confirm-checkout is called */
    public void recordCheckoutConfirmCartNotFound() {
        registry.counter("cart.checkout.confirm.cart_not_found").increment();
    }

    /**
     * Basket is missing or empty when confirm-checkout is called.
     * Combined counter — both cases block confirmation.
     */
    public void recordCheckoutConfirmBasketNotFound() {
        registry.counter("cart.checkout.confirm.basket_not_found").increment();
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
        registry.counter("cart.checkout.initiate.blocked", "store_id", storeId, "scope", scope).increment();
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
     * Fired when the 8-second gate timeout fires during confirmCheckout() — the
     * validation future was still running when the deadline expired. Signals a hung
     * downstream dependency (delivery or promotion service), not thread-pool saturation.
     * Distinct from execution errors and executor rejections.
     */
    public void recordCheckoutConfirmTimeout(String storeId) {
        registry.counter("cart.checkout.confirm.timeout", "store_id", storeId).increment();
    }

    /**
     * Fired when the 8-second gate timeout fires during initiateCheckout() — the
     * validation future was still running when the deadline expired. Signals a hung
     * downstream dependency (delivery or promotion service), not thread-pool saturation.
     * Distinct from execution errors and executor rejections.
     */
    public void recordCheckoutInitiateTimeout(String storeId) {
        registry.counter("cart.checkout.initiate.timeout", "store_id", storeId).increment();
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
    public void recordCheckoutConfirmRejected(String storeId, String scope) {
        registry.counter("cart.checkout.confirm.rejected", "store_id", storeId, "scope", scope).increment();
    }

    // ── Response cache ────────────────────────────────────────────────────────

    public void recordCacheHit() {
        cacheHit.increment();
    }

    public void recordCacheMiss() {
        cacheMiss.increment();
    }

    // ── CAS conflicts ─────────────────────────────────────────────────────────

    /**
     * Fired every time a CAS save fails because the cart was concurrently modified
     * between the read and the write. Maps to HTTP 409. Client should retry.
     * Distinct from any other 409 source — this counter is the only way to isolate
     * optimistic-lock contention from generic conflict responses.
     */
    public void recordCasConflict() {
        registry.counter("cart.cas.conflict").increment();
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
     * @param reason "timeout" | "circuit_open" | "error" | "null_response"
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
     * @param operation "availability" | "fee_quote" | "scheduled_quote" | "checkout_quote"
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
     * @param operation "availability" | "fee_quote" | "scheduled_quote" | "checkout_quote"
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

    // ── Schema migration ──────────────────────────────────────────────────────

    /**
     * Fired when Jackson throws during cart deserialization from Redis.
     * The corrupt key is deleted immediately. No version info is available.
     */
    public void recordSchemaDeserializationFailed() {
        registry.counter("cart.schema.deserialization_failed").increment();
    }

    /**
     * Fired when a stored cart has {@code schemaVersion < CURRENT_SCHEMA_VERSION}
     * and migration is about to be attempted.
     */
    public void recordSchemaMigrationAttempted(int fromVersion, int toVersion) {
        registry.counter("cart.schema.migration.attempted",
                "from_version", String.valueOf(fromVersion),
                "to_version",   String.valueOf(toVersion)).increment();
    }

    /**
     * Fired when the full migration chain completed and the post-migration CAS
     * save succeeded. The cart is now live at the new schema version.
     */
    public void recordSchemaMigrationSucceeded(int fromVersion, int toVersion) {
        registry.counter("cart.schema.migration.succeeded",
                "from_version", String.valueOf(fromVersion),
                "to_version",   String.valueOf(toVersion)).increment();
    }

    /**
     * Fired when migration completed in-memory but the CAS save failed because
     * a concurrent write changed the version key between the read and the save.
     * The original cart is untouched in Redis — the next read will retry migration.
     */
    public void recordSchemaMigrationCasConflict(int fromVersion, int toVersion) {
        registry.counter("cart.schema.migration.cas_conflict",
                "from_version", String.valueOf(fromVersion),
                "to_version",   String.valueOf(toVersion)).increment();
    }

    /**
     * Fired when {@code CartSchemaMigrationService.migrate()} throws.
     * The cart is unreadable — the user will receive an empty cart until the
     * underlying migration bug is fixed and the key is corrected or evicted.
     */
    public void recordSchemaMigrationFailed(int fromVersion, int toVersion) {
        registry.counter("cart.schema.migration.failed",
                "from_version", String.valueOf(fromVersion),
                "to_version",   String.valueOf(toVersion)).increment();
    }

    /**
     * Fired when a stored cart has {@code schemaVersion > CURRENT_SCHEMA_VERSION}.
     * Expected during rolling deployments — a newer pod wrote the cart and this
     * older pod cannot safely read it. Should drain to zero after rollout completes.
     *
     * @param foundVersion the schema version that was found in the stored cart
     */
    public void recordSchemaVersionTooNew(int foundVersion) {
        registry.counter("cart.schema.version_too_new",
                "found_version", String.valueOf(foundVersion)).increment();
    }

    /**
     * Start a wall-clock sample for the full migration chain execution.
     * Stop via {@link #stopSchemaMigrationTimer} in a finally block.
     * Covers both successful and failed migrations so slow steps are visible
     * regardless of outcome.
     */
    public Timer.Sample startSchemaMigrationTimer() {
        return Timer.start(registry);
    }

    /**
     * Stop and record the migration chain sample.
     * Always called from a finally block — covers success, CAS conflict, and exception.
     */
    public void stopSchemaMigrationTimer(Timer.Sample sample, int fromVersion, int toVersion) {
        sample.stop(Timer.builder("cart.schema.migration.duration")
                .tag("from_version", String.valueOf(fromVersion))
                .tag("to_version",   String.valueOf(toVersion))
                .publishPercentileHistogram()
                .register(registry));
    }
}
