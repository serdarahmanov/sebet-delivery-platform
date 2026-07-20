package com.sebet.order_service.scheduled.order;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ScheduledOrderActivationSchedulerTest {

    private final ScheduledOrderActivationService activationService = mock(ScheduledOrderActivationService.class);
    private final ScheduledOrderActivationScheduler scheduler =
            new ScheduledOrderActivationScheduler(activationService);

    @Test
    void activateDueScheduledOrdersDoesNothingWhenDisabled() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);
        ReflectionTestUtils.setField(scheduler, "activationLeadTime", Duration.ofMinutes(30));
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);

        scheduler.activateDueScheduledOrders();

        verify(activationService, never()).activateDueOrders(any(), any(), eq(100));
    }

    @Test
    void activateDueScheduledOrdersDelegatesWhenEnabled() {
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "activationLeadTime", Duration.ofMinutes(30));
        ReflectionTestUtils.setField(scheduler, "batchSize", 50);

        scheduler.activateDueScheduledOrders();

        verify(activationService).activateDueOrders(any(), eq(Duration.ofMinutes(30)), eq(50));
    }

    @Test
    void activateDueScheduledOrdersUsesShedLock() throws Exception {
        Method method = ScheduledOrderActivationScheduler.class
                .getMethod("activateDueScheduledOrders");

        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        org.assertj.core.api.Assertions.assertThat(lock).isNotNull();
        org.assertj.core.api.Assertions.assertThat(lock.name()).isEqualTo("scheduledOrderActivationJob");
        org.assertj.core.api.Assertions.assertThat(lock.lockAtMostFor())
                .isEqualTo("${order-service.scheduled-order.activation-job.lock-at-most-for}");
        org.assertj.core.api.Assertions.assertThat(lock.lockAtLeastFor())
                .isEqualTo("${order-service.scheduled-order.activation-job.lock-at-least-for}");
    }
}
