package com.sebet.order_service.scheduled.idempotency;

import com.sebet.order_service.persistence.repository.IdempotentCommandRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IdempotentCommandCleanupSchedulerTest {

    private final IdempotentCommandRepository repository = mock(IdempotentCommandRepository.class);
    private final IdempotentCommandCleanupScheduler scheduler =
            new IdempotentCommandCleanupScheduler(repository);

    @Test
    void cleanupDeletesExpiredRows() {
        ReflectionTestUtils.setField(scheduler, "completedRetention", Duration.ofDays(7));
        ReflectionTestUtils.setField(scheduler, "abandonedInProgressRetention", Duration.ofHours(1));

        scheduler.cleanup();

        verify(repository).deleteCompletedOlderThan(any(OffsetDateTime.class));
        verify(repository).deleteAbandonedInProgressOlderThan(any(OffsetDateTime.class));
    }

    @Test
    void cleanupUsesShedLock() throws Exception {
        Method method = IdempotentCommandCleanupScheduler.class.getMethod("cleanup");

        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("idempotentCommandCleanupJob");
        assertThat(lock.lockAtMostFor()).isEqualTo("${order-service.idempotency.cleanup-lock-at-most-for}");
        assertThat(lock.lockAtLeastFor()).isEqualTo("${order-service.idempotency.cleanup-lock-at-least-for}");
    }
}
