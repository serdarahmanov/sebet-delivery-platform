package com.sebet.order_service.scheduled.order;

import com.sebet.order_service.order.service.OrderLifecycleResult;
import com.sebet.order_service.order.service.OrderLifecycleService;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledOrderActivationService {

    private final OrderRepository orderRepository;
    private final OrderLifecycleService orderLifecycleService;

    public int activateDueOrders(OffsetDateTime now, Duration activationLeadTime, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be greater than zero");
        }
        OffsetDateTime activationCutoff = now.plus(activationLeadTime);
        List<OrderEntity> dueOrders = orderRepository.findByStatusAndScheduledForLessThanEqualOrderByScheduledForAsc(
                OrderStatus.SCHEDULED,
                activationCutoff,
                PageRequest.of(0, batchSize)
        );

        int activated = 0;
        for (OrderEntity order : dueOrders) {
            if (activateOne(order)) {
                activated++;
            }
        }
        if (activated > 0) {
            log.info("Activated scheduled orders count={} cutoff={}", activated, activationCutoff);
        }
        return activated;
    }

    private boolean activateOne(OrderEntity order) {
        String orderId = order.getId().toString();
        OrderLifecycleResult result;
        try {
            result = orderLifecycleService.activateScheduledWithoutRedisUpdate(orderId);
        } catch (RuntimeException exception) {
            log.warn("Failed to activate scheduled order orderId={}", orderId, exception);
            return false;
        }

        try {
            orderLifecycleService.updateScheduledActivationRedisViews(
                    result.order(),
                    result.changedAt(),
                    OrderLifecycleService.SCHEDULED_ORDER_AUTO_ACTIVATION_ACTION,
                    "scheduled-order-activation:" + orderId
            );
        } catch (RuntimeException exception) {
            log.warn("Scheduled order activated but Redis hot-view update failed orderId={}", orderId, exception);
        }
        return true;
    }
}
