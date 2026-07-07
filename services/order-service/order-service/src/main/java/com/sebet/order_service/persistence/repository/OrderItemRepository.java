package com.sebet.order_service.persistence.repository;

import com.sebet.order_service.persistence.entity.OrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItemEntity, UUID> {

    List<OrderItemEntity> findByOrderIdOrderByLineNumberAsc(UUID orderId);

    List<OrderItemEntity> findByOrderIdInOrderByOrderIdAscLineNumberAsc(Collection<UUID> orderIds);
}
