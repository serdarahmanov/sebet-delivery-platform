package com.sebet.order_service.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.internal.dto.request.SystemCancelOrderRequest;
import com.sebet.order_service.internal.dto.response.ActivateScheduledOrderResponse;
import com.sebet.order_service.internal.dto.response.CancelActiveProposalResponse;
import com.sebet.order_service.internal.dto.response.CancelProposalResponse;
import com.sebet.order_service.internal.dto.response.SystemCancelOrderResponse;
import com.sebet.order_service.order.service.OrderLifecycleResult;
import com.sebet.order_service.order.service.OrderLifecycleService;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.shared.idempotency.IdempotentCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class InternalOrderLifecycleService {

    private static final String ACTIVATE_SCHEDULED_ACTION =
            OrderLifecycleService.INTERNAL_ACTIVATE_SCHEDULED_ACTION;
    private static final String SYSTEM_CANCEL_ACTION =
            OrderLifecycleService.INTERNAL_SYSTEM_CANCEL_ACTION;
    private static final String ADMIN_CANCEL_ACTION =
            OrderLifecycleService.INTERNAL_ADMIN_CANCEL_ACTION;
    private static final String CANCEL_ACTIVE_PROPOSAL_ACTION =
            OrderLifecycleService.INTERNAL_CANCEL_ACTIVE_PROPOSAL_ACTION;
    private static final String CANCEL_PROPOSAL_AND_ORDER_ACTION =
            OrderLifecycleService.INTERNAL_CANCEL_PROPOSAL_AND_ORDER_ACTION;
    private static final EnumSet<OrderCancellationReason> SYSTEM_CANCEL_REASONS = EnumSet.of(
            OrderCancellationReason.PAYMENT_FAILED,
            OrderCancellationReason.NO_RIDERS_AVAILABLE,
            OrderCancellationReason.STORE_RESPONSE_TIMEOUT,
            OrderCancellationReason.AWAITING_CUSTOMER_RESPONSE_TIMEOUT,
            OrderCancellationReason.SYSTEM_ERROR
    );

    private final OrderLifecycleService orderLifecycleService;
    private final IdempotentCommandService idempotentCommandService;
    private final ObjectMapper objectMapper;

    public ActivateScheduledOrderResponse activateScheduled(String orderId, String idempotencyKey) {
        AtomicReference<OrderEntity> activatedOrder = new AtomicReference<>();
        AtomicReference<OffsetDateTime> changedAt = new AtomicReference<>();

        ActivateScheduledOrderResponse response = idempotentCommandService.execute(
                ACTIVATE_SCHEDULED_ACTION,
                idempotencyKey,
                orderId,
                "orderId=" + orderId,
                ActivateScheduledOrderResponse.class,
                () -> {
                    OrderLifecycleResult result = orderLifecycleService.activateScheduledWithoutRedisUpdate(orderId);
                    activatedOrder.set(result.order());
                    changedAt.set(result.changedAt());
                    return new ActivateScheduledOrderResponse(orderId, result.newStatus().name());
                }
        );

        if (activatedOrder.get() != null) {
            orderLifecycleService.updateScheduledActivationRedisViews(
                    activatedOrder.get(),
                    changedAt.get(),
                    idempotencyKey
            );
        } else {
            orderLifecycleService.updateScheduledActivationRedisViews(orderId, idempotencyKey);
        }

        return response;
    }

    public SystemCancelOrderResponse systemCancel(
            String orderId,
            SystemCancelOrderRequest request,
            String idempotencyKey
    ) {
        OrderCancellationReason reason = parseSystemCancellationReason(request);
        AtomicReference<OrderEntity> cancelledOrder = new AtomicReference<>();
        AtomicReference<OffsetDateTime> cancelledAt = new AtomicReference<>();

        SystemCancelOrderResponse response = idempotentCommandService.execute(
                SYSTEM_CANCEL_ACTION,
                idempotencyKey,
                orderId,
                "orderId=" + orderId + ";reason=" + reason.name(),
                SystemCancelOrderResponse.class,
                () -> {
                    OrderLifecycleResult result = orderLifecycleService.systemCancelWithoutRedisUpdate(
                            orderId,
                            reason,
                            writeMetadata(new SystemCancelMetadata(reason.name()))
                    );
                    cancelledOrder.set(result.order());
                    cancelledAt.set(result.changedAt());
                    return new SystemCancelOrderResponse(
                            orderId,
                            result.newStatus().name(),
                            reason.name(),
                            result.changedAt().toString()
                    );
                }
        );

        if (cancelledOrder.get() != null) {
            orderLifecycleService.evictSystemCancelledRedisViews(
                    cancelledOrder.get(),
                    cancelledAt.get(),
                    idempotencyKey
            );
        } else {
            orderLifecycleService.evictSystemCancelledRedisViews(orderId, idempotencyKey);
        }
        return response;
    }

    public SystemCancelOrderResponse adminCancel(
            String orderId,
            SystemCancelOrderRequest request,
            String idempotencyKey
    ) {
        OrderCancellationReason reason = parseSystemCancellationReason(request);
        AtomicReference<OrderEntity> cancelledOrder = new AtomicReference<>();
        AtomicReference<OffsetDateTime> cancelledAt = new AtomicReference<>();

        SystemCancelOrderResponse response = idempotentCommandService.execute(
                ADMIN_CANCEL_ACTION,
                idempotencyKey,
                orderId,
                "orderId=" + orderId + ";reason=" + reason.name(),
                SystemCancelOrderResponse.class,
                () -> {
                    OrderLifecycleResult result = orderLifecycleService.adminCancelWithoutRedisUpdate(
                            orderId,
                            reason,
                            writeMetadata(new SystemCancelMetadata(reason.name()))
                    );
                    cancelledOrder.set(result.order());
                    cancelledAt.set(result.changedAt());
                    return new SystemCancelOrderResponse(
                            orderId,
                            result.newStatus().name(),
                            reason.name(),
                            result.changedAt().toString()
                    );
                }
        );

        if (cancelledOrder.get() != null) {
            orderLifecycleService.evictAdminCancelledRedisViews(
                    cancelledOrder.get(),
                    cancelledAt.get(),
                    idempotencyKey
            );
        } else {
            orderLifecycleService.evictAdminCancelledRedisViews(orderId, idempotencyKey);
        }
        return response;
    }

    public CancelActiveProposalResponse cancelActiveProposal(String orderId, String idempotencyKey) {
        AtomicReference<OrderEntity> updatedOrder = new AtomicReference<>();

        CancelActiveProposalResponse response = idempotentCommandService.execute(
                CANCEL_ACTIVE_PROPOSAL_ACTION,
                idempotencyKey,
                orderId,
                "orderId=" + orderId,
                CancelActiveProposalResponse.class,
                () -> {
                    OrderLifecycleResult result =
                            orderLifecycleService.cancelActiveProposalWithoutRedisUpdate(orderId);
                    updatedOrder.set(result.order());
                    return new CancelActiveProposalResponse(
                            orderId,
                            result.newStatus().name(),
                            result.changedAt().toString()
                    );
                }
        );

        if (updatedOrder.get() != null) {
            orderLifecycleService.updateCancelActiveProposalRedisViews(updatedOrder.get(), idempotencyKey);
        } else {
            orderLifecycleService.updateCancelActiveProposalRedisViews(orderId, idempotencyKey);
        }
        return response;
    }

    public CancelProposalResponse cancelProposalAndOrder(String orderId, String idempotencyKey) {
        AtomicReference<OrderEntity> cancelledOrder = new AtomicReference<>();
        AtomicReference<OffsetDateTime> cancelledAt = new AtomicReference<>();

        CancelProposalResponse response = idempotentCommandService.execute(
                CANCEL_PROPOSAL_AND_ORDER_ACTION,
                idempotencyKey,
                orderId,
                "orderId=" + orderId,
                CancelProposalResponse.class,
                () -> {
                    OrderLifecycleResult result =
                            orderLifecycleService.cancelProposalAndOrderWithoutRedisUpdate(orderId);
                    cancelledOrder.set(result.order());
                    cancelledAt.set(result.changedAt());
                    return new CancelProposalResponse(
                            orderId,
                            result.newStatus().name(),
                            result.changedAt().toString()
                    );
                }
        );

        if (cancelledOrder.get() != null) {
            orderLifecycleService.evictCancelProposalAndOrderRedisViews(
                    cancelledOrder.get(), cancelledAt.get(), idempotencyKey);
        } else {
            orderLifecycleService.evictCancelProposalAndOrderRedisViews(orderId, idempotencyKey);
        }
        return response;
    }

    private OrderCancellationReason parseSystemCancellationReason(SystemCancelOrderRequest request) {
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        OrderCancellationReason reason;
        try {
            reason = OrderCancellationReason.valueOf(request.reason());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported internal cancellation reason: " + request.reason());
        }
        if (!SYSTEM_CANCEL_REASONS.contains(reason)) {
            throw new IllegalArgumentException("Unsupported internal cancellation reason: " + request.reason());
        }
        return reason;
    }

    private String writeMetadata(Object metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize internal order lifecycle metadata", exception);
        }
    }

    private record SystemCancelMetadata(String reason) {}
}
