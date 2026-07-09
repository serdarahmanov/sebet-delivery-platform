package com.sebet.order_service.internal.service;

import com.sebet.order_service.cache.service.OrderCacheEvictionService;
import com.sebet.order_service.internal.dto.request.AssignDriverRequest;
import com.sebet.order_service.internal.dto.request.UnassignDriverRequest;
import com.sebet.order_service.internal.dto.response.AssignDriverResponse;
import com.sebet.order_service.internal.dto.response.UnassignDriverResponse;
import com.sebet.order_service.order.event.OrderEventOutboxWriter;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.exception.OrderInvalidTransitionException;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import com.sebet.order_service.shared.idempotency.IdempotentCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InternalDriverAssignmentService {

    private static final String ASSIGN_DRIVER_ACTION = "ASSIGN_DRIVER";
    private static final String UNASSIGN_DRIVER_ACTION = "UNASSIGN_DRIVER";
    private static final String IDEMPOTENT_ASSIGN_DRIVER_ACTION = "INTERNAL_ASSIGN_DRIVER";
    private static final String IDEMPOTENT_UNASSIGN_DRIVER_ACTION = "INTERNAL_UNASSIGN_DRIVER";

    private final OrderRepository orderRepository;
    private final OrderEventOutboxWriter orderEventOutboxWriter;
    private final IdempotentCommandService idempotentCommandService;
    private final OrderCacheEvictionService orderCacheEvictionService;

    public AssignDriverResponse assignDriver(
            String orderId,
            AssignDriverRequest request,
            String idempotencyKey
    ) {
        AssignDriverResponse response = idempotentCommandService.execute(
                IDEMPOTENT_ASSIGN_DRIVER_ACTION,
                idempotencyKey,
                orderId,
                "orderId=" + orderId + ";driverId=" + request.driverId(),
                AssignDriverResponse.class,
                () -> assignDriverInTransaction(orderId, request)
        );
        orderCacheEvictionService.evictC2OrRequestEviction(orderId, IDEMPOTENT_ASSIGN_DRIVER_ACTION, idempotencyKey);
        return response;
    }

    private AssignDriverResponse assignDriverInTransaction(String orderId, AssignDriverRequest request) {
        OrderEntity order = loadOrder(orderId);
        rejectTerminal(orderId, order.getStatus(), ASSIGN_DRIVER_ACTION);

        String requestedDriverId = request.driverId();
        String currentDriverId = order.getDriverId();

        if (requestedDriverId.equals(currentDriverId)) {
            return new AssignDriverResponse(orderId, requestedDriverId, iso(order.getDriverAssignedAt()));
        }

        OffsetDateTime assignedAt = OffsetDateTime.now();
        order.setDriverId(requestedDriverId);
        order.setDriverAssignedAt(assignedAt);
        order.setUpdatedAt(assignedAt);
        OrderEntity savedOrder = orderRepository.saveAndFlush(order);

        if (currentDriverId == null) {
            orderEventOutboxWriter.saveDriverAssigned(savedOrder, requestedDriverId, assignedAt);
        } else {
            orderEventOutboxWriter.saveDriverReplaced(savedOrder, currentDriverId, requestedDriverId, assignedAt);
        }

        return new AssignDriverResponse(orderId, requestedDriverId, iso(assignedAt));
    }

    public UnassignDriverResponse unassignDriver(
            String orderId,
            UnassignDriverRequest request,
            String idempotencyKey
    ) {
        UnassignDriverResponse response = idempotentCommandService.execute(
                IDEMPOTENT_UNASSIGN_DRIVER_ACTION,
                idempotencyKey,
                orderId,
                "orderId=" + orderId + ";reason=" + request.reason(),
                UnassignDriverResponse.class,
                () -> unassignDriverInTransaction(orderId, request)
        );
        orderCacheEvictionService.evictC2OrRequestEviction(orderId, IDEMPOTENT_UNASSIGN_DRIVER_ACTION, idempotencyKey);
        return response;
    }

    private UnassignDriverResponse unassignDriverInTransaction(String orderId, UnassignDriverRequest request) {
        OrderEntity order = loadOrder(orderId);
        OrderStatus status = order.getStatus();
        rejectTerminal(orderId, status, UNASSIGN_DRIVER_ACTION);

        String previousDriverId = order.getDriverId();
        if (previousDriverId == null) {
            throw new OrderInvalidTransitionException(orderId, status, UNASSIGN_DRIVER_ACTION);
        }

        OffsetDateTime unassignedAt = OffsetDateTime.now();
        order.setDriverId(null);
        order.setDriverAssignedAt(null);
        order.setUpdatedAt(unassignedAt);
        OrderEntity savedOrder = orderRepository.saveAndFlush(order);

        orderEventOutboxWriter.saveDriverUnassigned(
                savedOrder,
                previousDriverId,
                unassignedAt,
                request.reason()
        );

        return new UnassignDriverResponse(orderId, previousDriverId, status.name(), request.reason());
    }

    private OrderEntity loadOrder(String orderId) {
        return orderRepository.findById(parseOrderId(orderId))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private void rejectTerminal(String orderId, OrderStatus status, String action) {
        if (status == OrderStatus.DELIVERED || status == OrderStatus.CANCELLED) {
            throw new OrderInvalidTransitionException(orderId, status, action);
        }
    }

    private UUID parseOrderId(String orderId) {
        try {
            return UUID.fromString(orderId);
        } catch (IllegalArgumentException exception) {
            throw new OrderNotFoundException(orderId);
        }
    }

    private String iso(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}
