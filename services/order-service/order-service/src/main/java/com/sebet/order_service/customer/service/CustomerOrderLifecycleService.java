package com.sebet.order_service.customer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.DeliveryAddress;
import com.sebet.order_service.cache.service.OrderRespondAcceptRedisUpdater;
import com.sebet.order_service.cache.service.OrderScheduledUpdateRedisWriter;
import com.sebet.order_service.customer.dto.request.RespondToOrderChangesRequest;
import com.sebet.order_service.customer.dto.request.UpdateScheduledOrderRequest;
import com.sebet.order_service.customer.dto.response.ActivateScheduledNowResponse;
import com.sebet.order_service.customer.dto.response.CancelOrderResponse;
import com.sebet.order_service.customer.dto.response.RespondToOrderChangesResponse;
import com.sebet.order_service.customer.dto.response.ScheduledOrderDetailResponse;
import com.sebet.order_service.integration.store.StoreServiceClient;
import com.sebet.order_service.integration.store.dto.StoreWorkingHoursResponse;
import com.sebet.order_service.order.event.OrderProposalAcceptedEventData;
import com.sebet.order_service.order.service.OrderLifecycleResult;
import com.sebet.order_service.order.service.OrderLifecycleService;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.exception.InvalidScheduledWindowException;
import com.sebet.order_service.shared.exception.ScheduledOrderModificationWindowClosedException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomerOrderLifecycleService {

    private final OrderLifecycleService orderLifecycleService;
    private final OrderRespondAcceptRedisUpdater orderRespondAcceptRedisUpdater;
    private final OrderScheduledUpdateRedisWriter scheduledUpdateRedisWriter;
    private final StoreServiceClient storeServiceClient;
    private final CustomerOrderQueryService queryService;
    private final ObjectMapper objectMapper;

    @Value("${order-service.scheduled-order.modification-cutoff-minutes:40}")
    private int modificationCutoffMinutes;

    @Value("${order-service.scheduled-order.min-lead-time-minutes:60}")
    private int minLeadTimeMinutes;

    @Value("${order-service.scheduled-order.slot-interval-minutes:15}")
    private int slotIntervalMinutes;

    @Value("${order-service.scheduled-order.delivery-window-duration-minutes:30}")
    private int deliveryWindowDurationMinutes;

    public ActivateScheduledNowResponse activateNow(String userId, String orderId) {
        OrderLifecycleResult result = orderLifecycleService.customerActivateScheduled(orderId, userId);
        String idempotencyKey = "CUSTOMER_ACTIVATE_NOW_" + orderId;
        orderLifecycleService.updateScheduledActivationRedisViews(result.order(), result.changedAt(), idempotencyKey);
        return new ActivateScheduledNowResponse(orderId, result.newStatus().name(), result.changedAt().toString());
    }

    public CancelOrderResponse cancelOrder(String userId, String orderId) {
        OrderLifecycleResult result = orderLifecycleService.customerCancelWithoutRedisUpdate(orderId, userId);
        String idempotencyKey = "CUSTOMER_CANCEL_" + orderId;
        orderLifecycleService.evictCustomerCancelledRedisViews(result.order(), result.changedAt(), idempotencyKey);
        return new CancelOrderResponse(orderId, result.newStatus().name(), result.changedAt().toString());
    }

    public ScheduledOrderDetailResponse updateScheduledOrder(
            String userId,
            String orderId,
            UpdateScheduledOrderRequest request
    ) {
        // Cross-field: at least one field must be present
        if (request.scheduledWindowStart() == null
                && request.newAddress() == null
                && request.phoneNumber() == null) {
            throw new IllegalArgumentException(
                    "At least one of scheduledWindowStart, newAddress, or phoneNumber must be provided");
        }

        // Load order first so we can check the modification window
        // (OrderLifecycleService will re-load inside the transaction — acceptable since
        //  scheduled orders are low-frequency and optimistic locking protects against races)
        OrderEntity currentOrder = orderLifecycleService.loadScheduledOrderForCustomer(orderId, userId);

        // 409 — modification window closed
        if (!OffsetDateTime.now().plusMinutes(modificationCutoffMinutes).isBefore(currentOrder.getScheduledFor())) {
            throw new ScheduledOrderModificationWindowClosedException(orderId);
        }

        // Validate and resolve the new scheduledFor value
        OffsetDateTime newScheduledFor = null;
        if (request.scheduledWindowStart() != null) {
            newScheduledFor = parseAndValidateScheduledWindow(
                    request.scheduledWindowStart(),
                    currentOrder.getScheduledFor(),
                    currentOrder.getStoreId()
            );
        }

        // Resolve address fields if address is being updated
        String newAddressJson = null;
        BigDecimal newLat = null;
        BigDecimal newLng = null;
        DeliveryAddress newCacheAddress = null;
        if (request.newAddress() != null) {
            UpdateScheduledOrderRequest.NewDeliveryAddress addr = request.newAddress();
            newAddressJson = buildAddressJson(addr);
            newLat = addr.lat();
            newLng = addr.lng();
            newCacheAddress = DeliveryAddress.builder()
                    .street(addr.street())
                    .city(addr.city())
                    .lat(addr.lat().doubleValue())
                    .lng(addr.lng().doubleValue())
                    .build();
        }

        // Persist — transactional
        final OffsetDateTime resolvedScheduledFor = newScheduledFor;
        final String resolvedAddressJson = newAddressJson;
        final BigDecimal resolvedLat = newLat;
        final BigDecimal resolvedLng = newLng;
        final DeliveryAddress resolvedCacheAddress = newCacheAddress;
        final String resolvedPhoneNumber = request.phoneNumber();
        final String idempotencyKey = "CUSTOMER_UPDATE_SCHEDULED_" + orderId;

        OrderEntity saved = orderLifecycleService.customerUpdateScheduled(
                orderId, userId,
                resolvedScheduledFor,
                resolvedAddressJson,
                resolvedLat,
                resolvedLng,
                resolvedPhoneNumber
        );

        // Redis update after commit
        registerAfterCommitOrRun(() -> scheduledUpdateRedisWriter.apply(
                saved.getStoreId(),
                orderId,
                resolvedScheduledFor != null ? resolvedScheduledFor.toInstant() : null,
                resolvedCacheAddress,
                resolvedPhoneNumber,
                idempotencyKey
        ));

        return queryService.getScheduledOrderDetail(userId, orderId);
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

    private OffsetDateTime parseAndValidateScheduledWindow(
            String rawValue,
            OffsetDateTime currentScheduledFor,
            String storeId
    ) {
        OffsetDateTime newWindow;
        try {
            newWindow = OffsetDateTime.parse(rawValue);
        } catch (DateTimeParseException e) {
            throw new InvalidScheduledWindowException(
                    "scheduledWindowStart is not a valid ISO-8601 timestamp: " + rawValue);
        }

        // Must be beyond the minimum lead time from now
        if (!newWindow.isAfter(OffsetDateTime.now().plusMinutes(minLeadTimeMinutes))) {
            throw new InvalidScheduledWindowException(
                    "scheduledWindowStart must be at least " + minLeadTimeMinutes
                            + " minutes in the future");
        }

        // Must differ from current by at least one slot interval
        long diffMinutes = Math.abs(java.time.Duration.between(currentScheduledFor, newWindow).toMinutes());
        if (diffMinutes < slotIntervalMinutes) {
            throw new InvalidScheduledWindowException(
                    "New scheduledWindowStart must differ from the current value by at least "
                            + slotIntervalMinutes + " minutes");
        }

        // Must align to slot interval boundary (minute % slotInterval == 0, seconds == 0)
        if (newWindow.getMinute() % slotIntervalMinutes != 0 || newWindow.getSecond() != 0) {
            throw new InvalidScheduledWindowException(
                    "scheduledWindowStart must align to a " + slotIntervalMinutes
                            + "-minute slot boundary (e.g. 14:00, 14:15, 14:30)");
        }

        // Store working hours validation
        StoreWorkingHoursResponse workingHours = storeServiceClient.getWorkingHours(storeId);
        DayOfWeek day = newWindow.getDayOfWeek();
        if (!workingHours.getWorkingDays().contains(day)) {
            throw new InvalidScheduledWindowException(
                    "The store is not open on " + day.name().toLowerCase());
        }

        LocalTime windowStart = newWindow.toLocalTime();
        LocalTime windowEnd = windowStart.plusMinutes(deliveryWindowDurationMinutes);
        if (windowStart.isBefore(workingHours.getOpenTime())
                || windowEnd.isAfter(workingHours.getCloseTime())) {
            throw new InvalidScheduledWindowException(
                    "The delivery window " + windowStart + "-" + windowEnd
                            + " falls outside store hours "
                            + workingHours.getOpenTime() + "-" + workingHours.getCloseTime());
        }

        return newWindow;
    }

    private String buildAddressJson(UpdateScheduledOrderRequest.NewDeliveryAddress addr) {
        try {
            return objectMapper.writeValueAsString(addr);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize new delivery address", e);
        }
    }

    private void registerAfterCommitOrRun(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }
}
