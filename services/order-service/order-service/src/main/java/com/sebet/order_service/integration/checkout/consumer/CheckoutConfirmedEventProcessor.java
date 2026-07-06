package com.sebet.order_service.integration.checkout.consumer;

import com.sebet.order_service.cache.repository.OrderLockRedisRepository;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedEvent;
import com.sebet.order_service.integration.checkout.mapper.CheckoutConfirmedEventMapper;
import com.sebet.order_service.order.command.CreateOrderCommand;
import com.sebet.order_service.order.command.CreateOrderResult;
import com.sebet.order_service.order.service.OrderCreationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CheckoutConfirmedEventProcessor {

    private final CheckoutConfirmedEventMapper checkoutConfirmedEventMapper;
    private final OrderCreationService orderCreationService;
    private final OrderLockRedisRepository orderLockRedisRepository;
    private final String instanceId;

    public CheckoutConfirmedEventProcessor(
            CheckoutConfirmedEventMapper checkoutConfirmedEventMapper,
            OrderCreationService orderCreationService,
            OrderLockRedisRepository orderLockRedisRepository,
            @Value("${order-service.instance-id:${spring.application.name:order-service}-${random.uuid}}")
            String instanceId
    ) {
        this.checkoutConfirmedEventMapper = checkoutConfirmedEventMapper;
        this.orderCreationService = orderCreationService;
        this.orderLockRedisRepository = orderLockRedisRepository;
        this.instanceId = instanceId;
    }

    public void process(CheckoutConfirmedEvent event) {
        String cartId = event.cartId();
        if (!orderLockRedisRepository.tryAcquire(cartId, instanceId)) {
            throw new CheckoutOrderLockUnavailableException(cartId);
        }

        try {
            CreateOrderCommand command = checkoutConfirmedEventMapper.toCreateOrderCommand(event);
            CreateOrderResult result = orderCreationService.createOrder(command);

            if (!result.createdNewOrder()) {
                log.info("Ignoring duplicate checkout event for cartId={}", cartId);
                return;
            }

            log.info("Created order id={} from checkout cartId={}", result.order().getId(), cartId);
        } finally {
            boolean released = orderLockRedisRepository.release(cartId, instanceId);
            if (!released) {
                log.warn("Checkout order lock was not released by this instance for cartId={}", cartId);
            }
        }
    }
}
