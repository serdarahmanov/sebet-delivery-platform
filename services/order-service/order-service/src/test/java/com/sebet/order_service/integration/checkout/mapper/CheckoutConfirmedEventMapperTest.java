package com.sebet.order_service.integration.checkout.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.integration.checkout.CheckoutEventTestFactory;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedPayload;
import com.sebet.order_service.integration.checkout.event.CheckoutScheduleType;
import com.sebet.order_service.integration.checkout.event.IntegrationEvent;
import com.sebet.order_service.order.command.CreateOrderCommand;
import com.sebet.order_service.order.command.CreateOrderItemCommand;
import com.sebet.order_service.shared.enums.ScheduleType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutConfirmedEventMapperTest {

    private final CheckoutConfirmedEventMapper mapper = new CheckoutConfirmedEventMapper(new ObjectMapper());

    @Test
    void mapsAsapCheckoutEnvelopeToCreateOrderCommand() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-1");

        CreateOrderCommand command = mapper.toCreateOrderCommand(event);

        assertThat(command.cartId()).isEqualTo("cart-1");
        assertThat(command.customerId()).isEqualTo("customer-1");
        assertThat(command.storeId()).isEqualTo("store-1");
        assertThat(command.scheduleType()).isEqualTo(ScheduleType.ASAP);
        assertThat(command.scheduledFor()).isNull();
        assertThat(command.subtotalAmount()).isEqualByComparingTo(new BigDecimal("33000.00"));
        assertThat(command.itemDiscountAmount()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(command.orderDiscountAmount()).isEqualByComparingTo(new BigDecimal("3000.00"));
        assertThat(command.deliveryFeeAmount()).isEqualByComparingTo(new BigDecimal("8000.00"));
        assertThat(command.totalAmount()).isEqualByComparingTo(new BigDecimal("36000.00"));
        assertThat(command.currency()).isEqualTo("UZS");
        assertThat(command.deliveryAddressJson())
                .contains("\"street\":\"Amir Temur 25\"")
                .contains("\"city\":\"Tashkent\"")
                .contains("\"addressId\":\"address-1\"");
        assertThat(command.deliveryLat()).isEqualByComparingTo(new BigDecimal("41.311100"));
        assertThat(command.deliveryLng()).isEqualByComparingTo(new BigDecimal("69.279700"));
        assertThat(command.storeLat()).isEqualByComparingTo(new BigDecimal("41.320100"));
        assertThat(command.storeLng()).isEqualByComparingTo(new BigDecimal("69.240500"));
        assertThat(command.serviceFeeAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(command.smallOrderFeeAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(command.feeQuoteId()).isEqualTo("quote-1");
        assertThat(command.selectedPromoCodes()).isEqualTo(List.of("PROMO10"));
    }

    @Test
    void mapsScheduledCheckoutEnvelopeToCreateOrderCommand() {
        Instant scheduledFor = Instant.parse("2026-07-09T10:00:00Z");
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent(
                UUID.randomUUID(),
                "cart-2",
                CheckoutScheduleType.SCHEDULED,
                scheduledFor
        );

        CreateOrderCommand command = mapper.toCreateOrderCommand(event);

        assertThat(command.scheduleType()).isEqualTo(ScheduleType.SCHEDULED);
        assertThat(command.scheduledFor()).isEqualTo(OffsetDateTime.parse("2026-07-09T10:00:00Z"));
    }

    @Test
    void preservesItemOrderAndItemAmounts() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-3");

        CreateOrderCommand command = mapper.toCreateOrderCommand(event);

        assertThat(command.items())
                .extracting(CreateOrderItemCommand::productId)
                .containsExactly("product-1", "product-2");
        assertThat(command.items().get(0).grossAmount()).isEqualByComparingTo(new BigDecimal("24000.00"));
        assertThat(command.items().get(0).discountAmount()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(command.items().get(0).netAmount()).isEqualByComparingTo(new BigDecimal("22000.00"));
        assertThat(command.items().get(1).grossAmount()).isEqualByComparingTo(new BigDecimal("9000.00"));
        assertThat(command.items().get(1).discountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(command.items().get(1).netAmount()).isEqualByComparingTo(new BigDecimal("9000.00"));
        assertThat(command.items().get(0).sku()).isEqualTo("sku-1");
        assertThat(command.items().get(1).sku()).isEqualTo("sku-2");
    }
}
