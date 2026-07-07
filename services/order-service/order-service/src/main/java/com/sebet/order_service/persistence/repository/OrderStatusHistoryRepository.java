package com.sebet.order_service.persistence.repository;

import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.shared.enums.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistoryEntity, UUID> {

    List<OrderStatusHistoryEntity> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    Optional<OrderStatusHistoryEntity> findFirstByOrderIdAndToStatus(UUID orderId, OrderStatus toStatus);
}
