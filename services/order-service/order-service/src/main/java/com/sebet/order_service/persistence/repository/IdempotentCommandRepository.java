package com.sebet.order_service.persistence.repository;

import com.sebet.order_service.persistence.entity.IdempotentCommandEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotentCommandRepository extends JpaRepository<IdempotentCommandEntity, UUID> {

    Optional<IdempotentCommandEntity> findByActionAndIdempotencyKey(String action, String idempotencyKey);
}
