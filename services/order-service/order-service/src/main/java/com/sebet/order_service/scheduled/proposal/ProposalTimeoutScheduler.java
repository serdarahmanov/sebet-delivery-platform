package com.sebet.order_service.scheduled.proposal;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
public class ProposalTimeoutScheduler {

    private final ProposalTimeoutService proposalTimeoutService;

    @Value("${order-service.proposal-timeout-job.enabled}")
    private boolean enabled;

    @Value("${order-service.proposal-timeout-job.response-window}")
    private Duration responseWindow;

    @Value("${order-service.proposal-timeout-job.batch-size}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${order-service.proposal-timeout-job.interval-ms}",
            initialDelayString = "${order-service.proposal-timeout-job.initial-delay-ms}"
    )
    @SchedulerLock(
            name = "proposalTimeoutJob",
            lockAtMostFor = "${order-service.proposal-timeout-job.lock-at-most-for}",
            lockAtLeastFor = "${order-service.proposal-timeout-job.lock-at-least-for}"
    )
    public void cancelExpiredProposals() {
        if (!enabled) {
            return;
        }
        proposalTimeoutService.cancelExpiredProposals(OffsetDateTime.now(), responseWindow, batchSize);
    }
}
