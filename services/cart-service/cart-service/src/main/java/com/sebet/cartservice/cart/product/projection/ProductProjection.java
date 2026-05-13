package com.sebet.cartservice.cart.product.projection;


import com.sebet.cartservice.cart.enums.ProductUnit;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;


@Entity
@Table (
        name = "cart_product_projections",
        indexes = {
                @Index(name = "idx_cart_product_projection_product_id", columnList = "product_id"),
                @Index(name = "idx_cart_product_projection_store_id", columnList = "store_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name="uk_cart_product_projection_product_store",
                        columnNames = {"product_id", "store_id"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductProjection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, length = 100)
    private String productId;

    @Column(name = "store_id", nullable = false, length = 100)
    private String storeId;

    @Column(name = "sku", length = 100)
    private String sku;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "brand_name")
    private String brandName;

    @Column(name = "category_id", nullable = false, length = 100)
    private String categoryId;

    @Column(name = "category_name")
    private String categoryName;

    @Column(name = "image_url")
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 50)
    private ProductUnit unit;

    @Column(name = "min_quantity", nullable = false, precision = 19, scale = 3)
    private BigDecimal minQuantity;

    @Column(name = "max_quantity", precision = 19, scale = 3)
    private BigDecimal maxQuantity;

    @Column(name = "quantity_step", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityStep;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "original_unit_price", precision = 19, scale = 2)
    private BigDecimal originalUnitPrice;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "sellable", nullable = false)
    private Boolean sellable;

    @Column(name = "product_version")
    private Long productVersion;

    @Column(name = "price_version")
    private Long priceVersion;

    @Column(name = "product_updated_at")
    private Instant productUpdatedAt;

    @Column(name = "price_updated_at")
    private Instant priceUpdatedAt;

    @Column(name = "projection_updated_at", nullable = false)
    private Instant projectionUpdatedAt;


    @PrePersist
    public void prePersist() {
        if (projectionUpdatedAt == null) {
            projectionUpdatedAt = Instant.now();
        }

        if (active == null) {
            active = true;
        }

        if (sellable == null) {
            sellable = true;
        }

        if (minQuantity == null) {
            minQuantity = BigDecimal.ONE;
        }

        if (quantityStep == null) {
            quantityStep = BigDecimal.ONE;
        }
    }

    @PreUpdate
    public void preUpdate() {
        projectionUpdatedAt = Instant.now();
    }
}
