package com.sebet.order_service.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "processed_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEventEntity {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime processedAt;

    private OffsetDateTime occurredAt;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(length = 150)
    private String lockedBy;

    private OffsetDateTime lockedUntil;

    private OffsetDateTime completedAt;

    @PrePersist
    void prePersist() {
        if (processedAt == null) {
            processedAt = OffsetDateTime.now();
        }
        if (status == null) {
            status = "COMPLETED";
        }
        if ("COMPLETED".equals(status) && completedAt == null) {
            completedAt = processedAt;
        }
    }
}
