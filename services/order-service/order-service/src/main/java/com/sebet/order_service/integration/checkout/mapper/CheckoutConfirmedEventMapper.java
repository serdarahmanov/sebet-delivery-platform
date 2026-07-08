package com.sebet.order_service.integration.checkout.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedPayload;
import com.sebet.order_service.integration.checkout.event.CheckoutItemPayload;
import com.sebet.order_service.integration.checkout.event.CheckoutScheduleType;
import com.sebet.order_service.integration.checkout.event.IntegrationEvent;
import com.sebet.order_service.integration.checkout.event.MoneyBreakdown;
import com.sebet.order_service.integration.checkout.event.StoreLocationSnapshot;
import com.sebet.order_service.order.command.CreateOrderCommand;
import com.sebet.order_service.order.command.CreateOrderItemCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CheckoutConfirmedEventMapper {

    private final ObjectMapper objectMapper;

    public CreateOrderCommand toCreateOrderCommand(IntegrationEvent<CheckoutConfirmedPayload> event) {
        CheckoutConfirmedPayload payload = event.data();
        MoneyBreakdown money = payload.money();
        StoreLocationSnapshot storeLocation = payload.storeLocation();
        return new CreateOrderCommand(
                payload.cartId(),
                payload.customerId(),
                payload.storeId(),
                toScheduleType(payload.scheduleType()),
                toOffsetDateTime(payload.scheduledFor()),
                amount(money.subtotalAmount()),
                amount(money.itemDiscountAmount()),
                amount(money.orderDiscountAmount()),
                amount(money.deliveryFeeAmount()),
                amount(money.serviceFeeAmount()),
                amount(money.smallOrderFeeAmount()),
                amount(money.totalAmount()),
                money.currency(),
                toJson(payload),
                payload.deliveryAddress().lat(),
                payload.deliveryAddress().lng(),
                storeLocation.lat(),
                storeLocation.lng(),
                payload.feeQuoteId(),
                payload.selectedPromoCodes(),
                toItemCommands(payload.items())
        );
    }

    private String toJson(CheckoutConfirmedPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload.deliveryAddress());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize checkout delivery address", exception);
        }
    }

    private List<CreateOrderItemCommand> toItemCommands(List<CheckoutItemPayload> items) {
        return items.stream()
                .map(item -> new CreateOrderItemCommand(
                        item.productId(),
                        item.productName(),
                        item.quantity(),
                        com.sebet.order_service.shared.enums.ProductUnit.valueOf(item.unit()),
                        amount(item.unitPriceAmount()),
                        amount(item.grossAmount()),
                        amount(item.discountAmount()),
                        amount(item.netAmount()),
                        item.imageUrl(),
                        item.sku()
                ))
                .toList();
    }

    private com.sebet.order_service.shared.enums.ScheduleType toScheduleType(CheckoutScheduleType scheduleType) {
        if (scheduleType == CheckoutScheduleType.SCHEDULED) {
            return com.sebet.order_service.shared.enums.ScheduleType.SCHEDULED;
        }
        return com.sebet.order_service.shared.enums.ScheduleType.ASAP;
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private BigDecimal amount(Long amount) {
        return amount == null ? null : BigDecimal.valueOf(amount);
    }
}
