package com.sebet.order_service.persistence.entity;

import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.shared.enums.OrderCancelledBy;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ScheduleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String customerId;

    @Column(nullable = false, length = 64)
    private String storeId;

    @Column(nullable = false, length = 64)
    private String cartId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleType scheduleType;

    private OffsetDateTime scheduledFor;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotalAmount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal itemDiscountAmount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal orderDiscountAmount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal deliveryFeeAmount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String deliveryAddressJson;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal deliveryLat;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal deliveryLng;

    @Column(precision = 9, scale = 6)
    private BigDecimal storeLat;

    @Column(precision = 9, scale = 6)
    private BigDecimal storeLng;

    @Column(length = 64)
    private String driverId;

    private OffsetDateTime driverAssignedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 80)
    private OrderCancellationReason cancellationReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private OrderCancelledBy cancelledBy;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    private OffsetDateTime deliveredAt;

    private OffsetDateTime cancelledAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
