package com.sebet.order_service.scheduled.checkout;

import com.sebet.order_service.persistence.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessedEventCleanupScheduler {

    private final ProcessedEventRepository repository;

    @Value("${order-service.kafka.checkout-events.processed-events.completed-retention}")
    private Duration completedRetention;

    @Value("${order-service.kafka.checkout-events.processed-events.abandoned-in-progress-retention}")
    private Duration abandonedInProgressRetention;

    @Transactional
    @Scheduled(fixedDelayString = "${order-service.kafka.checkout-events.processed-events.cleanup-interval-ms}")
    public void cleanup() {
        OffsetDateTime now = OffsetDateTime.now();
        int completed = repository.deleteCompletedOlderThan(now.minus(completedRetention));
        int abandoned = repository.deleteAbandonedInProgressOlderThan(now.minus(abandonedInProgressRetention));
        if (completed > 0 || abandoned > 0) {
            log.info(
                    "Cleaned processed checkout event rows completed={} abandonedInProgress={}",
                    completed,
                    abandoned
            );
        }
    }
}
