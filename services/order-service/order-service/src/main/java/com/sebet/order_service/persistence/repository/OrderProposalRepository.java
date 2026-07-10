package com.sebet.order_service.persistence.repository;

import com.sebet.order_service.persistence.entity.OrderProposalEntity;
import com.sebet.order_service.shared.enums.ProposalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderProposalRepository extends JpaRepository<OrderProposalEntity, UUID> {

    Optional<OrderProposalEntity> findByOrderId(UUID orderId);

    Optional<OrderProposalEntity> findByOrderIdAndStatus(UUID orderId, ProposalStatus status);
}
