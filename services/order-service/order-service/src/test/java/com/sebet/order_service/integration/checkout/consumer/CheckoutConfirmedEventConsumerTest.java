package com.sebet.order_service.integration.checkout.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sebet.order_service.integration.checkout.CheckoutEventTestFactory;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedPayload;
import com.sebet.order_service.integration.checkout.event.IntegrationEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class CheckoutConfirmedEventConsumerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final CheckoutConfirmedHandler handler = mock(CheckoutConfirmedHandler.class);
    private final CheckoutConfirmedEventConsumer consumer = new CheckoutConfirmedEventConsumer(objectMapper, handler);

    @Test
    void deserializesRawJsonEnvelopeAndDelegatesToHandler() throws Exception {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-1");
        String payload = objectMapper.writeValueAsString(event);

        consumer.consume(payload);

        ArgumentCaptor<IntegrationEvent<CheckoutConfirmedPayload>> captor = ArgumentCaptor.forClass(IntegrationEvent.class);
        verify(handler).handle(captor.capture());
        verifyNoMoreInteractions(handler);

        assertThat(captor.getValue().eventId()).isEqualTo(event.eventId());
        assertThat(captor.getValue().eventType()).isEqualTo("CheckoutConfirmed");
        assertThat(captor.getValue().eventVersion()).isEqualTo(1);
        assertThat(captor.getValue().data().cartId()).isEqualTo("cart-1");
        assertThat(captor.getValue().data().scheduleType().name()).isEqualTo("ASAP");
    }

    @Test
    void rejectsMalformedJsonBeforeHandler() {
        assertThatThrownBy(() -> consumer.consume("{not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize CheckoutConfirmed event");

        verifyNoMoreInteractions(handler);
    }

    @Test
    void rejectsImmediateScheduleType() throws Exception {
        String payload = objectMapper.writeValueAsString(CheckoutEventTestFactory.checkoutEvent("cart-immediate"));
        String immediatePayload = payload.replace("\"scheduleType\":\"ASAP\"", "\"scheduleType\":\"IMMEDIATE\"");

        assertThatThrownBy(() -> consumer.consume(immediatePayload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize CheckoutConfirmed event");

        verifyNoMoreInteractions(handler);
    }

    @Test
    void listenerConsumesRawStringPayload() throws Exception {
        Method consume = CheckoutConfirmedEventConsumer.class.getDeclaredMethod("consume", String.class);

        assertThat(consume.getParameterTypes()).containsExactly(String.class);
    }
}
