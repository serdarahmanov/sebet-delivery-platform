package com.sebet.order_service.integration.checkout.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedEvent;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedItem;
import com.sebet.order_service.integration.checkout.event.CheckoutStoreLocation;
import com.sebet.order_service.order.command.CreateOrderCommand;
import com.sebet.order_service.order.command.CreateOrderItemCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class CheckoutConfirmedEventMapper {

    private final ObjectMapper objectMapper;

    public CreateOrderCommand toCreateOrderCommand(CheckoutConfirmedEvent event) {
        CheckoutStoreLocation storeLocation = event.storeLocation();
        return new CreateOrderCommand(
                event.cartId(),
                event.customerId(),
                event.storeId(),
                event.scheduleType(),
                event.scheduledFor(),
                event.subtotalAmount(),
                event.itemDiscountAmount(),
                event.orderDiscountAmount(),
                event.deliveryFeeAmount(),
                event.totalAmount(),
                event.currency(),
                toJson(event),
                event.deliveryAddress().lat(),
                event.deliveryAddress().lng(),
                storeLocation == null ? null : storeLocation.lat(),
                storeLocation == null ? null : storeLocation.lng(),
                toItemCommands(event.items())
        );
    }

    private String toJson(CheckoutConfirmedEvent event) {
        try {
            return objectMapper.writeValueAsString(event.deliveryAddress());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize checkout delivery address", exception);
        }
    }

    private List<CreateOrderItemCommand> toItemCommands(List<CheckoutConfirmedItem> items) {
        return items.stream()
                .map(item -> new CreateOrderItemCommand(
                        item.productId(),
                        item.productName(),
                        item.quantity(),
                        item.unit(),
                        item.unitPriceAmount(),
                        item.grossAmount(),
                        item.discountAmount(),
                        item.netAmount(),
                        item.imageUrl()
                ))
                .toList();
    }
}
