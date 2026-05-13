package com.sebet.cartservice.cart.inventory.projection;


import com.sebet.cartservice.cart.enums.StockStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;


@Entity
@Table(
        name = "cart_inventory_projections",
        indexes = {
                @Index(name = "idx_cart_inventory_projection_product_id", columnList = "product_id"),
                @Index(name = "idx_cart_inventory_projection_store_id", columnList = "store_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_cart_inventory_projection_product_store",
                        columnNames = {"product_id", "store_id"}
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder

public class InventoryProjection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, length = 100)
    private String productId;

    @Column(name = "store_id", nullable = false, length = 100)
    private String storeId;

    @Column(name = "available_quantity", precision = 19, scale = 3)
    private BigDecimal availableQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_status", nullable = false, length = 50)
    private StockStatus stockStatus;

    @Column(name = "available", nullable = false)
    private Boolean available;

    @Column(name = "inventory_version")
    private Long inventoryVersion;

    @Column(name = "inventory_updated_at")
    private Instant inventoryUpdatedAt;

    @Column(name = "projection_updated_at", nullable = false)
    private Instant projectionUpdatedAt;

    @PrePersist
    public void prePersist() {
        if (projectionUpdatedAt == null) {
            projectionUpdatedAt = Instant.now();
        }

        if (available == null) {
            available = false;
        }
    }

    @PreUpdate
    public void preUpdate() {
        projectionUpdatedAt = Instant.now();
    }
}
