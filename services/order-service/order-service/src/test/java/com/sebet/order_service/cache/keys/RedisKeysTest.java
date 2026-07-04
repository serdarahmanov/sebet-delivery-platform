package com.sebet.order_service.cache.keys;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisKeysTest {

    @Test
    void activeOrdersBuildsExpectedKey() {
        assertThat(RedisKeys.activeOrders("user-123"))
                .isEqualTo("user:active_orders:user-123");
    }

    @Test
    void storeActiveOrdersBuildsExpectedKey() {
        assertThat(RedisKeys.storeActiveOrders("store-123"))
                .isEqualTo("store:active_orders:store-123");
    }

    @Test
    void storeScheduledOrdersBuildsExpectedKey() {
        assertThat(RedisKeys.storeScheduledOrders("store-123"))
                .isEqualTo("store:scheduled_orders:store-123");
    }

    @Test
    void orderBuildsExpectedKey() {
        assertThat(RedisKeys.order("order-123"))
                .isEqualTo("order:order-123");
    }

    @Test
    void orderTrackingBuildsExpectedKey() {
        assertThat(RedisKeys.orderTracking("order-123"))
                .isEqualTo("order:tracking:order-123");
    }

    @Test
    void orderStatusBuildsExpectedKey() {
        assertThat(RedisKeys.orderStatus("order-123"))
                .isEqualTo("order:status:order-123");
    }

    @Test
    void orderLockBuildsExpectedKey() {
        assertThat(RedisKeys.orderLock("cart-123"))
                .isEqualTo("order:lock:cart-123");
    }

    @Test
    void orderTimelineBuildsExpectedKey() {
        assertThat(RedisKeys.orderTimeline("order-123"))
                .isEqualTo("order:timeline:order-123");
    }

    @Test
    void orderVerificationBuildsExpectedKey() {
        assertThat(RedisKeys.orderVerification("order-123"))
                .isEqualTo("order:verification:order-123");
    }

    @Test
    void orderProposalsBuildsExpectedKey() {
        assertThat(RedisKeys.orderProposals("order-123"))
                .isEqualTo("order:proposals:order-123");
    }
}
