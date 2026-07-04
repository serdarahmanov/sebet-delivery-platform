package com.sebet.order_service.integration.checkout.consumer;

import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedEvent;
import com.sebet.order_service.integration.checkout.mapper.CheckoutConfirmedEventMapper;
import com.sebet.order_service.order.command.CreateOrderCommand;
import com.sebet.order_service.order.command.CreateOrderResult;
import com.sebet.order_service.order.service.OrderCreationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CheckoutConfirmedEventConsumer {

    private final CheckoutConfirmedEventMapper checkoutConfirmedEventMapper;
    private final OrderCreationService orderCreationService;

    @KafkaListener(
            topics = "${order-service.kafka.checkout-events.topic}",
            groupId = "${order-service.kafka.checkout-events.group-id}",
            autoStartup = "${order-service.kafka.checkout-events.auto-startup:false}"
    )
    public void consume(CheckoutConfirmedEvent event) {
        CreateOrderCommand command = checkoutConfirmedEventMapper.toCreateOrderCommand(event);
        CreateOrderResult result = orderCreationService.createOrder(command);

        if (!result.createdNewOrder()) {
            log.info("Ignoring duplicate checkout event for cartId={}", event.cartId());
            return;
        }

        log.info("Created order id={} from checkout cartId={}", result.order().getId(), event.cartId());
    }
}
