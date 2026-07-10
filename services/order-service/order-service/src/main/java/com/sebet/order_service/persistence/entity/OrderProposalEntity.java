package com.sebet.order_service.persistence.entity;

import com.sebet.order_service.shared.enums.ProposalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tracks the full lifecycle of a store-initiated change proposal.
 *
 * Store side  : items_json, store_id, proposed_at, status=ACTIVE
 * Customer side: global_decision, item_decisions_json, responded_at, status=ACCEPTED|REJECTED
 * Promo side  : applied_at, status=APPLIED (after promo service calls back with recalculated totals)
 */

@Entity
@Table(name = "order_proposals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderProposalEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, length = 64, updatable = false)
    private String storeId;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime proposedAt;

    @Column(nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String itemsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProposalStatus status;

    /** ACCEPT_ALL, ACCEPT_WITH_MODIFICATIONS, or CANCEL_ORDER — set when customer responds. */
    @Column(length = 40)
    private String globalDecision;

    /** Per-item choices — populated only for ACCEPT_WITH_MODIFICATIONS. */
    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String itemDecisionsJson;

    /** When the customer submitted their response. */
    private OffsetDateTime respondedAt;

    /** When the promo service called back and the order was fully updated. */
    private OffsetDateTime appliedAt;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (status == null) status = ProposalStatus.ACTIVE;
    }
}
