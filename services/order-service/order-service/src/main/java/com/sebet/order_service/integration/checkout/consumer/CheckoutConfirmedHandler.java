package com.sebet.order_service.integration.checkout.consumer;

import com.sebet.order_service.cache.repository.OrderLockRedisRepository;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedPayload;
import com.sebet.order_service.integration.checkout.event.CheckoutItemPayload;
import com.sebet.order_service.integration.checkout.event.CheckoutScheduleType;
import com.sebet.order_service.integration.checkout.event.IntegrationEvent;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.integration.checkout.mapper.CheckoutConfirmedEventMapper;
import com.sebet.order_service.order.command.CreateOrderCommand;
import com.sebet.order_service.order.command.CreateOrderResult;
import com.sebet.order_service.integration.checkout.service.ProcessedEventWriter;
import com.sebet.order_service.order.service.OrderCreationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Slf4j
@Component
public class CheckoutConfirmedHandler {

    private static final String EVENT_TYPE = "CheckoutConfirmed";
    private static final int EVENT_VERSION = 1;
    private static final String AGGREGATE_TYPE = "Cart";

    private final CheckoutConfirmedEventMapper checkoutConfirmedEventMapper;
    private final OrderCreationService orderCreationService;
    private final OrderLockRedisRepository orderLockRedisRepository;
    private final ProcessedEventWriter processedEventWriter;
    private final String instanceId;

    public CheckoutConfirmedHandler(
            CheckoutConfirmedEventMapper checkoutConfirmedEventMapper,
            OrderCreationService orderCreationService,
            OrderLockRedisRepository orderLockRedisRepository,
            ProcessedEventWriter processedEventWriter,
            @Value("${order-service.instance-id:${spring.application.name:order-service}-${random.uuid}}")
            String instanceId
    ) {
        this.checkoutConfirmedEventMapper = checkoutConfirmedEventMapper;
        this.orderCreationService = orderCreationService;
        this.orderLockRedisRepository = orderLockRedisRepository;
        this.processedEventWriter = processedEventWriter;
        this.instanceId = instanceId;
    }

    public void handle(IntegrationEvent<CheckoutConfirmedPayload> event) {
        validate(event);
        if (processedEventWriter.isAlreadyProcessed(event.eventId())) {
            log.info("Ignoring already processed checkout event eventId={} cartId={}",
                    event.eventId(), event.data().cartId());
            return;
        }

        String cartId = event.data().cartId();
        if (!orderLockRedisRepository.tryAcquire(cartId, instanceId)) {
            throw new CheckoutOrderLockUnavailableException(cartId);
        }

        try {
            CreateOrderCommand command = checkoutConfirmedEventMapper.toCreateOrderCommand(event);
            CreateOrderResult result = orderCreationService.createOrder(command);

            if (!result.createdNewOrder()) {
                log.info("Ignoring duplicate checkout event for cartId={}", cartId);
            } else {
                log.info("Created order id={} from checkout cartId={}", result.order().getId(), cartId);
            }

            try {
                processedEventWriter.markProcessed(event);
            } catch (RuntimeException exception) {
                log.error(
                        "Failed to record processed checkout event after order creation and Redis initialization; " +
                                "Kafka should redeliver cartId={} eventId={}",
                        cartId,
                        event.eventId(),
                        exception
                );
                throw exception;
            }
        } finally {
            boolean released = orderLockRedisRepository.release(cartId, instanceId);
            if (!released) {
                log.warn("Checkout order lock was not released by this instance for cartId={}", cartId);
            }
        }
    }

    private void validate(IntegrationEvent<CheckoutConfirmedPayload> event) {
        Objects.requireNonNull(event, "event must not be null");
        require(event.eventId() != null, "eventId must not be null");
        require(EVENT_TYPE.equals(event.eventType()), "eventType must equal CheckoutConfirmed");
        require(Integer.valueOf(EVENT_VERSION).equals(event.eventVersion()), "eventVersion must equal 1");
        require(AGGREGATE_TYPE.equals(event.aggregateType()), "aggregateType must equal Cart");
        require(event.data() != null, "data must not be null");

        CheckoutConfirmedPayload data = event.data();
        require(event.aggregateId() != null && event.aggregateId().equals(data.cartId()),
                "aggregateId must equal data.cartId");
        require(data.customerId() != null, "data.customerId must not be null");
        require(data.storeId() != null, "data.storeId must not be null");
        require(data.money() != null, "data.money must not be null");
        require(data.money().subtotalAmount() != null, "data.money.subtotalAmount must not be null");
        require(data.money().itemDiscountAmount() != null, "data.money.itemDiscountAmount must not be null");
        require(data.money().orderDiscountAmount() != null, "data.money.orderDiscountAmount must not be null");
        require(data.money().deliveryFeeAmount() != null, "data.money.deliveryFeeAmount must not be null");
        require(data.money().serviceFeeAmount() != null, "data.money.serviceFeeAmount must not be null");
        require(data.money().smallOrderFeeAmount() != null, "data.money.smallOrderFeeAmount must not be null");
        require(data.money().totalAmount() != null, "data.money.totalAmount must not be null");
        require(data.money().currency() != null, "data.money.currency must not be null");
        require(data.items() != null && !data.items().isEmpty(), "data.items must not be empty");
        require(data.deliveryAddress() != null, "data.deliveryAddress must not be null");
        require(data.storeLocation() != null, "data.storeLocation must not be null");
        require(data.storeLocation().lat() != null, "data.storeLocation.lat must not be null");
        require(data.storeLocation().lng() != null, "data.storeLocation.lng must not be null");
        require(data.scheduleType() != null, "data.scheduleType must not be null");
        require(List.of(CheckoutScheduleType.ASAP, CheckoutScheduleType.SCHEDULED).contains(data.scheduleType()),
                "data.scheduleType must be ASAP or SCHEDULED");
        if (data.scheduleType() == CheckoutScheduleType.SCHEDULED) {
            require(data.scheduledFor() != null, "scheduledFor must not be null when scheduleType is SCHEDULED");
        }
        if (data.scheduleType() == CheckoutScheduleType.ASAP) {
            require(data.scheduledFor() == null, "scheduledFor must be null when scheduleType is ASAP");
        }
        validateItems(data.items());
    }

    private void validateItems(List<CheckoutItemPayload> items) {
        for (int i = 0; i < items.size(); i++) {
            CheckoutItemPayload item = items.get(i);
            String p = "data.items[" + i + "]";
            require(item != null, p + " must not be null");
            require(item.productId() != null, p + ".productId must not be null");
            require(item.productName() != null, p + ".productName must not be null");
            require(item.unit() != null, p + ".unit must not be null");
            try {
                ProductUnit.valueOf(item.unit());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(p + ".unit '" + item.unit() + "' is not a valid ProductUnit");
            }
            require(item.quantity() != null, p + ".quantity must not be null");
            require(item.unitPriceAmount() != null, p + ".unitPriceAmount must not be null");
            require(item.grossAmount() != null, p + ".grossAmount must not be null");
            require(item.discountAmount() != null, p + ".discountAmount must not be null");
            require(item.netAmount() != null, p + ".netAmount must not be null");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
