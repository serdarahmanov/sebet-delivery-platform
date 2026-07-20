package com.sebet.order_service.scheduled.proposal;

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
public class ProposalTimeoutService {

    private final OrderRepository orderRepository;
    private final OrderLifecycleService orderLifecycleService;

    public int cancelExpiredProposals(OffsetDateTime now, Duration responseWindow, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be greater than zero");
        }
        OffsetDateTime proposedBeforeCutoff = now.minus(responseWindow);
        List<OrderEntity> expiredOrders = orderRepository.findByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(
                OrderStatus.AWAITING_CUSTOMER_RESPONSE,
                proposedBeforeCutoff,
                PageRequest.of(0, batchSize)
        );

        int cancelled = 0;
        for (OrderEntity order : expiredOrders) {
            if (cancelOne(order)) {
                cancelled++;
            }
        }
        if (cancelled > 0) {
            log.info("Cancelled timed-out proposals count={} cutoff={}", cancelled, proposedBeforeCutoff);
        }
        return cancelled;
    }

    private boolean cancelOne(OrderEntity order) {
        String orderId = order.getId().toString();
        String idempotencyKey = "proposal-timeout:" + orderId;
        OrderLifecycleResult result;
        try {
            result = orderLifecycleService.cancelProposalAndOrderWithoutRedisUpdate(orderId);
        } catch (RuntimeException exception) {
            log.warn("Failed to cancel timed-out proposal orderId={}", orderId, exception);
            return false;
        }

        try {
            orderLifecycleService.evictCancelProposalAndOrderRedisViews(
                    result.order(),
                    result.changedAt(),
                    idempotencyKey
            );
        } catch (RuntimeException exception) {
            log.warn("Proposal timed out but Redis hot-view eviction failed orderId={}", orderId, exception);
        }
        return true;
    }
}
