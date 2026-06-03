package com.sebet.order_service.cache.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cache 2 — order:{orderId}
 *
 * Full static snapshot of an order.  Written once on order creation and
 * never mutated during delivery.  Serves the REST API response when the
 * tracking screen mounts (order summary, address, store pin, static driver
 * profile, item list).
 *
 * GPS coordinates are intentionally absent — they live in RedisOrderTracking
 * so that high-frequency GPS writes never touch this larger blob.
 *
 * TTL: 48 hours.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RedisOrder {

    private String orderId;
    private String userId;
    private String cartId;
    private BigDecimal totalAmount;
    private DeliveryAddress deliveryAddress;
    private StoreLocation storeLocation;
    private List<OrderItem> items;
    private DriverInfo driver;
    /** ISO-8601 timestamp */
    private String estimatedDeliveryAt;
    /** ISO-8601 timestamp */
    private String createdAt;
    /** ISO-8601 timestamp */
    private String updatedAt;
}
