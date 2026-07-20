package com.sebet.order_service.scheduled.order;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
public class ScheduledOrderActivationScheduler {

    private final ScheduledOrderActivationService activationService;

    @Value("${order-service.scheduled-order.activation-job.enabled}")
    private boolean enabled;

    @Value("${order-service.scheduled-order.activation-job.lead-time}")
    private Duration activationLeadTime;

    @Value("${order-service.scheduled-order.activation-job.batch-size}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${order-service.scheduled-order.activation-job.interval-ms}",
            initialDelayString = "${order-service.scheduled-order.activation-job.initial-delay-ms}"
    )
    @SchedulerLock(
            name = "scheduledOrderActivationJob",
            lockAtMostFor = "${order-service.scheduled-order.activation-job.lock-at-most-for}",
            lockAtLeastFor = "${order-service.scheduled-order.activation-job.lock-at-least-for}"
    )
    public void activateDueScheduledOrders() {
        if (!enabled) {
            return;
        }
        activationService.activateDueOrders(OffsetDateTime.now(), activationLeadTime, batchSize);
    }
}
