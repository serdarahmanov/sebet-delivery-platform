package com.sebet.order_service.customer.service;

import com.sebet.order_service.cache.service.OrderRespondAcceptRedisUpdater;
import com.sebet.order_service.customer.dto.request.RespondToOrderChangesRequest;
import com.sebet.order_service.customer.dto.response.CancelOrderResponse;
import com.sebet.order_service.customer.dto.response.RespondToOrderChangesResponse;
import com.sebet.order_service.order.event.OrderProposalAcceptedEventData;
import com.sebet.order_service.order.service.OrderLifecycleResult;
import com.sebet.order_service.order.service.OrderLifecycleService;
import com.sebet.order_service.shared.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomerOrderLifecycleService {

    private final OrderLifecycleService orderLifecycleService;
    private final OrderRespondAcceptRedisUpdater orderRespondAcceptRedisUpdater;

    public CancelOrderResponse cancelOrder(String userId, String orderId) {
        OrderLifecycleResult result = orderLifecycleService.customerCancelWithoutRedisUpdate(orderId, userId);
        String idempotencyKey = "CUSTOMER_CANCEL_" + orderId;
        orderLifecycleService.evictCustomerCancelledRedisViews(result.order(), result.changedAt(), idempotencyKey);
        return new CancelOrderResponse(orderId, result.newStatus().name(), result.changedAt().toString());
    }

    public RespondToOrderChangesResponse respondToChanges(
            String userId,
            String orderId,
            RespondToOrderChangesRequest request
    ) {
        if (request.globalDecision() == RespondToOrderChangesRequest.GlobalDecision.CANCEL_ORDER) {
            return handleCancelViaProposal(userId, orderId);
        }
        return handleAccept(userId, orderId, request);
    }

    private RespondToOrderChangesResponse handleCancelViaProposal(String userId, String orderId) {
        OrderLifecycleResult result = orderLifecycleService.customerRespondCancelWithoutRedisUpdate(orderId, userId);
        // C8 is included in the CancelledOrderHotViewsCacheEvictionStrategy Lua script — no separate delete needed.
        String idempotencyKey = "CUSTOMER_CANCEL_VIA_PROPOSAL_" + orderId;
        orderLifecycleService.evictCustomerCancelledRedisViews(result.order(), result.changedAt(), idempotencyKey);
        return new RespondToOrderChangesResponse(
                orderId,
                OrderStatus.CANCELLED,
                result.changedAt().toString(),
                "Order cancelled as requested."
        );
    }

    private RespondToOrderChangesResponse handleAccept(
            String userId,
            String orderId,
            RespondToOrderChangesRequest request
    ) {
        if (request.globalDecision() == RespondToOrderChangesRequest.GlobalDecision.ACCEPT_WITH_MODIFICATIONS
                && (request.itemDecisions() == null || request.itemDecisions().isEmpty())) {
            throw new IllegalArgumentException(
                    "itemDecisions must not be empty when globalDecision is ACCEPT_WITH_MODIFICATIONS");
        }

        String globalDecision = request.globalDecision().name();
        List<OrderProposalAcceptedEventData.ItemDecisionData> itemDecisions =
                request.globalDecision() == RespondToOrderChangesRequest.GlobalDecision.ACCEPT_WITH_MODIFICATIONS
                ? request.itemDecisions().stream()
                        .map(d -> new OrderProposalAcceptedEventData.ItemDecisionData(
                                d.productId(),
                                d.action().name(),
                                d.customQuantity()
                        ))
                        .toList()
                : null;

        OrderLifecycleResult result = orderLifecycleService.customerRespondAcceptWithoutRedisUpdate(
                orderId, userId, globalDecision, itemDecisions);

        String idempotencyKey = "ACCEPT_PROPOSAL_C8_" + orderId;
        orderRespondAcceptRedisUpdater.apply(orderId, idempotencyKey);

        return new RespondToOrderChangesResponse(
                orderId,
                OrderStatus.AWAITING_CUSTOMER_RESPONSE,
                result.changedAt().toString(),
                "Your response has been recorded. The order will resume once pricing is recalculated."
        );
    }
}
