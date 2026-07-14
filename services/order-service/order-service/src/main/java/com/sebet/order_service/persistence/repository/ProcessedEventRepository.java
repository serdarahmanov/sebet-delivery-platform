package com.sebet.order_service.persistence.repository;

import com.sebet.order_service.persistence.entity.ProcessedEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ProcessedEventEntity event
            set event.lockedBy = :lockedBy,
                event.lockedUntil = :lockedUntil,
                event.processedAt = :now
            where event.eventId = :eventId
              and event.status = 'IN_PROGRESS'
              and event.lockedUntil < :now
            """)
    int reclaimExpiredInProgress(
            @Param("eventId") UUID eventId,
            @Param("lockedBy") String lockedBy,
            @Param("lockedUntil") OffsetDateTime lockedUntil,
            @Param("now") OffsetDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ProcessedEventEntity event
            set event.status = 'COMPLETED',
                event.lockedBy = null,
                event.lockedUntil = null,
                event.completedAt = :now,
                event.processedAt = :now
            where event.eventId = :eventId
              and event.status = 'IN_PROGRESS'
              and event.lockedBy = :lockedBy
            """)
    int markCompleted(
            @Param("eventId") UUID eventId,
            @Param("lockedBy") String lockedBy,
            @Param("now") OffsetDateTime now
    );

    long deleteByEventIdAndStatusAndLockedBy(UUID eventId, String status, String lockedBy);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from ProcessedEventEntity event
            where event.status = 'COMPLETED'
              and event.completedAt < :cutoff
            """)
    int deleteCompletedOlderThan(@Param("cutoff") OffsetDateTime cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from ProcessedEventEntity event
            where event.status = 'IN_PROGRESS'
              and event.lockedUntil < :cutoff
            """)
    int deleteAbandonedInProgressOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
