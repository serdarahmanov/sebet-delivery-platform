package com.sebet.order_service.scheduled.idempotency;

import com.sebet.order_service.persistence.repository.IdempotentCommandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentCommandCleanupScheduler {

    private final IdempotentCommandRepository repository;

    @Value("${order-service.idempotency.completed-retention}")
    private Duration completedRetention;

    @Value("${order-service.idempotency.abandoned-in-progress-retention}")
    private Duration abandonedInProgressRetention;

    @Transactional
    @Scheduled(fixedDelayString = "${order-service.idempotency.cleanup-interval-ms}")
    @SchedulerLock(
            name = "idempotentCommandCleanupJob",
            lockAtMostFor = "${order-service.idempotency.cleanup-lock-at-most-for}",
            lockAtLeastFor = "${order-service.idempotency.cleanup-lock-at-least-for}"
    )
    public void cleanup() {
        OffsetDateTime now = OffsetDateTime.now();
        int completed = repository.deleteCompletedOlderThan(now.minus(completedRetention));
        int abandoned = repository.deleteAbandonedInProgressOlderThan(now.minus(abandonedInProgressRetention));
        if (completed > 0 || abandoned > 0) {
            log.info(
                    "Cleaned idempotent command rows completed={} abandonedInProgress={}",
                    completed,
                    abandoned
            );
        }
    }
}
