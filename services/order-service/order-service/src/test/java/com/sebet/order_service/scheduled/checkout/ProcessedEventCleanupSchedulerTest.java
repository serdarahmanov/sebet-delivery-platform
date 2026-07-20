package com.sebet.order_service.scheduled.checkout;

import com.sebet.order_service.persistence.repository.ProcessedEventRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProcessedEventCleanupSchedulerTest {

    private final ProcessedEventRepository repository = mock(ProcessedEventRepository.class);
    private final ProcessedEventCleanupScheduler scheduler =
            new ProcessedEventCleanupScheduler(repository);

    @Test
    void cleanupDeletesExpiredRowsUsingTheirOwnRetentionCutoffs() {
        Duration completedRetention = Duration.ofDays(7);
        Duration abandonedInProgressRetention = Duration.ofHours(1);
        ReflectionTestUtils.setField(scheduler, "completedRetention", completedRetention);
        ReflectionTestUtils.setField(scheduler, "abandonedInProgressRetention", abandonedInProgressRetention);

        OffsetDateTime beforeCall = OffsetDateTime.now();
        scheduler.cleanup();
        OffsetDateTime afterCall = OffsetDateTime.now();

        ArgumentCaptor<OffsetDateTime> completedCutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> abandonedCutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).deleteCompletedOlderThan(completedCutoffCaptor.capture());
        verify(repository).deleteAbandonedInProgressOlderThan(abandonedCutoffCaptor.capture());

        OffsetDateTime completedCutoff = completedCutoffCaptor.getValue();
        OffsetDateTime abandonedCutoff = abandonedCutoffCaptor.getValue();

        assertThat(completedCutoff).isBetween(
                beforeCall.minus(completedRetention).minus(2, ChronoUnit.SECONDS),
                afterCall.minus(completedRetention).plus(2, ChronoUnit.SECONDS)
        );
        assertThat(abandonedCutoff).isBetween(
                beforeCall.minus(abandonedInProgressRetention).minus(2, ChronoUnit.SECONDS),
                afterCall.minus(abandonedInProgressRetention).plus(2, ChronoUnit.SECONDS)
        );
        // completedRetention (7d) is much longer than abandonedInProgressRetention (1h), so the
        // completed-rows cutoff must land far earlier than the abandoned-rows cutoff. If the two
        // durations were ever swapped when passed to the repository, this ordering would flip.
        assertThat(completedCutoff).isBefore(abandonedCutoff.minusDays(6));
    }

    @Test
    void cleanupUsesShedLock() throws Exception {
        Method method = ProcessedEventCleanupScheduler.class.getMethod("cleanup");

        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("processedCheckoutEventCleanupJob");
        assertThat(lock.lockAtMostFor())
                .isEqualTo("${order-service.kafka.checkout-events.processed-events.cleanup-lock-at-most-for}");
        assertThat(lock.lockAtLeastFor())
                .isEqualTo("${order-service.kafka.checkout-events.processed-events.cleanup-lock-at-least-for}");
    }
}
