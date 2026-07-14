package com.sebet.order_service.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.service.OrderCreationRedisWriter;
import com.sebet.order_service.order.command.CreateOrderCommand;
import com.sebet.order_service.order.command.CreateOrderItemCommand;
import com.sebet.order_service.order.command.CreateOrderResult;
import com.sebet.order_service.order.event.OrderEventOutboxWriter;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.shared.enums.ScheduleType;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCreationServiceDuplicateCartRaceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final OrderStatusHistoryRepository orderStatusHistoryRepository = mock(OrderStatusHistoryRepository.class);
    private final OrderCreationRedisWriter orderCreationRedisWriter = mock(OrderCreationRedisWriter.class);
    private final OrderEventOutboxWriter orderEventOutboxWriter = mock(OrderEventOutboxWriter.class);
    private final PlatformTransactionManager transactionManager = new TestTransactionManager();
    private final OrderCreationService service = new OrderCreationService(
            orderRepository,
            orderItemRepository,
            orderStatusHistoryRepository,
            orderCreationRedisWriter,
            orderEventOutboxWriter,
            new ObjectMapper(),
            transactionManager
    );

    @Test
    void duplicateCartIdUniqueViolationLoadsExistingOrderAsIdempotentSuccess() {
        CreateOrderCommand command = command("cart-race");
        OrderEntity attemptedOrder = order("cart-race");
        OrderEntity existingOrder = order("cart-race");

        when(orderRepository.findByCartId("cart-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingOrder));
        when(orderRepository.save(any(OrderEntity.class))).thenReturn(attemptedOrder);
        doThrow(new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint \"idx_orders_cart_id\""
        )).when(orderRepository).flush();

        CreateOrderResult result = service.createOrder(command);

        assertThat(result.createdNewOrder()).isFalse();
        assertThat(result.order()).isSameAs(existingOrder);
        verify(orderCreationRedisWriter).ensureCreatedOrder(existingOrder);
    }

    private CreateOrderCommand command(String cartId) {
        return new CreateOrderCommand(
                cartId,
                "customer-1",
                "store-1",
                ScheduleType.ASAP,
                null,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("100.00"),
                "UZS",
                "{\"street\":\"A\",\"city\":\"Tashkent\"}",
                null,
                new BigDecimal("41.311100"),
                new BigDecimal("69.279700"),
                new BigDecimal("41.320100"),
                new BigDecimal("69.240500"),
                null,
                List.of(),
                List.of(new CreateOrderItemCommand(
                        "product-1",
                        "Apples",
                        BigDecimal.ONE,
                        ProductUnit.KG,
                        new BigDecimal("100.00"),
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("100.00"),
                        null,
                        null
                ))
        );
    }

    private OrderEntity order(String cartId) {
        OffsetDateTime now = OffsetDateTime.now();
        return OrderEntity.builder()
                .id(UUID.randomUUID())
                .cartId(cartId)
                .customerId("customer-1")
                .storeId("store-1")
                .status(OrderStatus.PENDING)
                .scheduleType(ScheduleType.ASAP)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() throws TransactionException {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
        }
    }
}
