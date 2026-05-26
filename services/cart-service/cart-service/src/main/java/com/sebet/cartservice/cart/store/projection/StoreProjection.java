package com.sebet.cartservice.cart.store.projection;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "cart_store_projection")
public class StoreProjection {

    @Id
    @Column(name = "store_id", nullable = false, length = 100)
    private String storeId;

    @Column(name = "store_name", nullable = false)
    private String storeName;

    @Column(name = "store_logo_url")
    private String storeLogoUrl;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "open", nullable = false)
    private Boolean open;

    @Column(name = "accepting_orders", nullable = false)
    private Boolean acceptingOrders;

    @Column(name = "minimum_order_amount", precision = 19, scale = 2)
    private BigDecimal minimumOrderAmount;

    @Column(name = "free_delivery_threshold", precision = 19, scale = 2)
    private BigDecimal freeDeliveryThreshold;

    @Column(name = "base_delivery_fee", precision = 19, scale = 2)
    private BigDecimal baseDeliveryFee;

    @Column(name = "estimated_preparation_minutes")
    private Integer estimatedPreparationMinutes;

    @Column(name = "store_version")
    private Long storeVersion;

    @Column(name = "store_updated_at", nullable = false)
    private Instant storeUpdatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();

        if (createdAt == null) {
            createdAt = now;
        }

        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }


}
