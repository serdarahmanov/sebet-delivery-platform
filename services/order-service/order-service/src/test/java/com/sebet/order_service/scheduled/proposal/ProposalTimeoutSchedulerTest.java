package com.sebet.order_service.scheduled.proposal;

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

class ProposalTimeoutSchedulerTest {

    private final ProposalTimeoutService proposalTimeoutService = mock(ProposalTimeoutService.class);
    private final ProposalTimeoutScheduler scheduler =
            new ProposalTimeoutScheduler(proposalTimeoutService);

    @Test
    void cancelExpiredProposalsDoesNothingWhenDisabled() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);
        ReflectionTestUtils.setField(scheduler, "responseWindow", Duration.ofHours(1));
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);

        scheduler.cancelExpiredProposals();

        verify(proposalTimeoutService, never()).cancelExpiredProposals(any(), any(), eq(100));
    }

    @Test
    void cancelExpiredProposalsDelegatesWhenEnabled() {
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "responseWindow", Duration.ofHours(1));
        ReflectionTestUtils.setField(scheduler, "batchSize", 50);

        scheduler.cancelExpiredProposals();

        verify(proposalTimeoutService).cancelExpiredProposals(any(), eq(Duration.ofHours(1)), eq(50));
    }

    @Test
    void cancelExpiredProposalsUsesShedLock() throws Exception {
        Method method = ProposalTimeoutScheduler.class
                .getMethod("cancelExpiredProposals");

        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        org.assertj.core.api.Assertions.assertThat(lock).isNotNull();
        org.assertj.core.api.Assertions.assertThat(lock.name()).isEqualTo("proposalTimeoutJob");
        org.assertj.core.api.Assertions.assertThat(lock.lockAtMostFor())
                .isEqualTo("${order-service.proposal-timeout-job.lock-at-most-for}");
        org.assertj.core.api.Assertions.assertThat(lock.lockAtLeastFor())
                .isEqualTo("${order-service.proposal-timeout-job.lock-at-least-for}");
    }
}
