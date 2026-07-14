package com.sebet.order_service.persistence.repository;

import com.sebet.order_service.persistence.entity.IdempotentCommandEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface IdempotentCommandRepository extends JpaRepository<IdempotentCommandEntity, UUID> {

    Optional<IdempotentCommandEntity> findByActionAndIdempotencyKey(String action, String idempotencyKey);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IdempotentCommandEntity command
            set command.lockedBy = :lockedBy,
                command.lockedUntil = :lockedUntil,
                command.updatedAt = :now
            where command.action = :action
              and command.idempotencyKey = :idempotencyKey
              and command.requestHash = :requestHash
              and command.status = 'IN_PROGRESS'
              and command.lockedUntil < :now
            """)
    int reclaimExpiredInProgress(
            @Param("action") String action,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("lockedBy") String lockedBy,
            @Param("lockedUntil") OffsetDateTime lockedUntil,
            @Param("now") OffsetDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from IdempotentCommandEntity command
            where command.status = 'COMPLETED'
              and command.completedAt < :cutoff
            """)
    int deleteCompletedOlderThan(@Param("cutoff") OffsetDateTime cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from IdempotentCommandEntity command
            where command.status = 'IN_PROGRESS'
              and command.lockedUntil < :cutoff
            """)
    int deleteAbandonedInProgressOlderThan(@Param("cutoff") OffsetDateTime cutoff);

    long deleteByActionAndIdempotencyKeyAndRequestHashAndStatusAndLockedBy(
            String action,
            String idempotencyKey,
            String requestHash,
            String status,
            String lockedBy
    );
}
