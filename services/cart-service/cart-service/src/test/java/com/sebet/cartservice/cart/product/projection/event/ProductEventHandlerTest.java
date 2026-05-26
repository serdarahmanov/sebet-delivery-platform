package com.sebet.cartservice.cart.product.projection.event;

import com.sebet.cartservice.cart.enums.ProductUnit;
import com.sebet.cartservice.cart.product.projection.ProductProjection;
import com.sebet.cartservice.cart.product.projection.ProductProjectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductEventHandlerTest {
    @Mock
    private ProductProjectionRepository repository;
    @InjectMocks
    private ProductEventHandler handler;

    @Test
    void unknownEventTypeDoesNotThrow() {
        ProductEvent event = baseEvent("evt-1", "ProductViewed");

        assertThatCode(() -> handler.handle(event)).doesNotThrowAnyException();
        verify(repository, never()).save(any());
    }

    @Test
    void nullEventDoesNotThrow() {
        assertThatCode(() -> handler.handle(null)).doesNotThrowAnyException();
        verify(repository, never()).save(any());
    }

    @Test
    void missingEventTypeDoesNotThrow() {
        ProductEvent event = new ProductEvent(
                "evt-2",
                " ",
                "product-1",
                "Product",
                1L,
                Instant.now(),
                "product-service",
                sampleData()
        );

        assertThatCode(() -> handler.handle(event)).doesNotThrowAnyException();
        verify(repository, never()).save(any());
    }

    @Test
    void knownEventTypeStillProcessesAndSaves() {
        ProductEvent event = baseEvent("evt-3", "ProductCreated");
        when(repository.findByProductIdAndStoreId("product-1", "store-1"))
                .thenReturn(Optional.empty());

        handler.handle(event);

        verify(repository).findByProductIdAndStoreId("product-1", "store-1");
        verify(repository).save(any(ProductProjection.class));
    }

    @Test
    void repositoryFailureFromKnownHandlerPropagates() {
        ProductEvent event = baseEvent("evt-4", "ProductCreated");
        when(repository.findByProductIdAndStoreId("product-1", "store-1"))
                .thenReturn(Optional.empty());
        when(repository.save(any(ProductProjection.class)))
                .thenThrow(new RuntimeException("db failure"));

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("db failure");

        verify(repository).save(any(ProductProjection.class));
    }

    @Test
    void missingEventIdDoesNotThrow() {
        ProductEvent event = new ProductEvent(
                null,
                "ProductCreated",
                "product-1",
                "Product",
                1L,
                Instant.now(),
                "product-service",
                sampleData()
        );

        assertThatCode(() -> handler.handle(event)).doesNotThrowAnyException();
        verify(repository, never()).findByProductIdAndStoreId(eq("product-1"), eq("store-1"));
        verify(repository, never()).save(any());
    }

    private ProductEvent baseEvent(String eventId, String eventType) {
        return new ProductEvent(
                eventId,
                eventType,
                "product-1",
                "Product",
                1L,
                Instant.now(),
                "product-service",
                sampleData()
        );
    }

    private ProductEventData sampleData() {
        return new ProductEventData(
                "product-1",
                "store-1",
                "SKU-1",
                "Apple",
                "Brand",
                "cat-1",
                "Fruit",
                "img",
                ProductUnit.PCS,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.valueOf(12),
                true,
                true,
                false,
                1L,
                1L,
                Instant.now(),
                Instant.now(),
                null
        );
    }
}
