package com.sebet.order_service.persistence.repository;

import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.shared.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    Optional<OrderEntity> findByIdAndCustomerId(UUID id, String customerId);

    Optional<OrderEntity> findByIdAndStoreId(UUID id, String storeId);

    Optional<OrderEntity> findByCartId(String cartId);

    Page<OrderEntity> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);

    Page<OrderEntity> findByCustomerIdAndStatusIn(String customerId, Collection<OrderStatus> statuses, Pageable pageable);

    Page<OrderEntity> findByStoreIdOrderByCreatedAtDesc(String storeId, Pageable pageable);

    List<OrderEntity> findByCustomerIdAndStatusInOrderByCreatedAtDesc(
            String customerId,
            Collection<OrderStatus> statuses
    );

    List<OrderEntity> findByStoreIdAndStatusInOrderByCreatedAtDesc(
            String storeId,
            Collection<OrderStatus> statuses
    );
}
