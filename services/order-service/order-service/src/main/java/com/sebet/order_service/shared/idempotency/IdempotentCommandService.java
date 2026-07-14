package com.sebet.order_service.shared.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.persistence.entity.IdempotentCommandEntity;
import com.sebet.order_service.persistence.repository.IdempotentCommandRepository;
import com.sebet.order_service.shared.exception.IdempotencyKeyConflictException;
import com.sebet.order_service.shared.exception.IdempotencyRequestInProgressException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IdempotentCommandService {

    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final IdempotentCommandRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final Duration inProgressLease;
    private final String instanceId;

    public IdempotentCommandService(
            IdempotentCommandRepository repository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${order-service.idempotency.in-progress-lease:PT2M}") Duration inProgressLease,
            @Value("${order-service.instance-id:${spring.application.name:order-service}-${random.uuid}}")
            String instanceId
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
        this.inProgressLease = inProgressLease;
        this.instanceId = instanceId;
    }

    public <T> T execute(
            String action,
            String idempotencyKey,
            String orderId,
            String requestFingerprint,
            Class<T> responseType,
            Supplier<T> operation
    ) {
        String normalizedKey = normalizeKey(idempotencyKey);
        String requestHash = sha256(requestFingerprint);
        String ownerToken = instanceId + ":" + UUID.randomUUID();

        IdempotentCommandEntity reservation = reserve(action, normalizedKey, orderId, requestHash, ownerToken);
        if (STATUS_COMPLETED.equals(reservation.getStatus())) {
            return replay(reservation, action, requestHash, responseType);
        }

        return runAndComplete(action, normalizedKey, requestHash, ownerToken, operation);
    }

    private IdempotentCommandEntity reserve(
            String action,
            String idempotencyKey,
            String orderId,
            String requestHash,
            String ownerToken
    ) {
        try {
            return requiresNewTransactionTemplate.execute(status -> {
                OffsetDateTime now = OffsetDateTime.now();
                IdempotentCommandEntity entity = IdempotentCommandEntity.builder()
                        .idempotencyKey(idempotencyKey)
                        .action(action)
                        .orderId(orderId)
                        .requestHash(requestHash)
                        .status(STATUS_IN_PROGRESS)
                        .lockedBy(ownerToken)
                        .lockedUntil(now.plus(inProgressLease))
                        .build();
                return repository.saveAndFlush(entity);
            });
        } catch (DataIntegrityViolationException duplicateKey) {
            return resolveExistingReservation(action, idempotencyKey, requestHash, ownerToken);
        }
    }

    private IdempotentCommandEntity resolveExistingReservation(
            String action,
            String idempotencyKey,
            String requestHash,
            String ownerToken
    ) {
        return requiresNewTransactionTemplate.execute(status -> {
            IdempotentCommandEntity existing = repository.findByActionAndIdempotencyKey(action, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Idempotency reservation disappeared"));

            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IdempotencyKeyConflictException(action);
            }
            if (STATUS_COMPLETED.equals(existing.getStatus())) {
                return existing;
            }
            if (STATUS_IN_PROGRESS.equals(existing.getStatus()) && isExpired(existing)) {
                OffsetDateTime now = OffsetDateTime.now();
                int reclaimed = repository.reclaimExpiredInProgress(
                        action,
                        idempotencyKey,
                        requestHash,
                        ownerToken,
                        now.plus(inProgressLease),
                        now
                );
                if (reclaimed == 1) {
                    existing.setLockedBy(ownerToken);
                    existing.setLockedUntil(now.plus(inProgressLease));
                    return existing;
                }
            }

            throw new IdempotencyRequestInProgressException(action);
        });
    }

    private <T> T runAndComplete(
            String action,
            String idempotencyKey,
            String requestHash,
            String ownerToken,
            Supplier<T> operation
    ) {
        try {
            return transactionTemplate.execute(status -> {
                T response = operation.get();
                IdempotentCommandEntity entity = repository.findByActionAndIdempotencyKey(action, idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("Idempotency reservation disappeared"));
                if (!entity.getRequestHash().equals(requestHash)) {
                    throw new IdempotencyKeyConflictException(action);
                }
                if (!ownerToken.equals(entity.getLockedBy())) {
                    throw new IdempotencyRequestInProgressException(action);
                }
                entity.setResponseJson(toJson(response));
                entity.setStatus(STATUS_COMPLETED);
                entity.setLockedBy(null);
                entity.setLockedUntil(null);
                entity.setCompletedAt(OffsetDateTime.now());
                repository.saveAndFlush(entity);
                return response;
            });
        } catch (RuntimeException | Error exception) {
            releaseInProgressReservation(action, idempotencyKey, requestHash, ownerToken);
            throw exception;
        }
    }

    private <T> T replay(
            IdempotentCommandEntity existing,
            String action,
            String requestHash,
            Class<T> responseType
    ) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyConflictException(action);
        }
        if (existing.getResponseJson() == null) {
            throw new IdempotencyRequestInProgressException(action);
        }
        return fromJson(existing.getResponseJson(), responseType);
    }

    private void releaseInProgressReservation(
            String action,
            String idempotencyKey,
            String requestHash,
            String ownerToken
    ) {
        requiresNewTransactionTemplate.executeWithoutResult(status ->
                repository.deleteByActionAndIdempotencyKeyAndRequestHashAndStatusAndLockedBy(
                        action,
                        idempotencyKey,
                        requestHash,
                        STATUS_IN_PROGRESS,
                        ownerToken
                )
        );
    }

    private boolean isExpired(IdempotentCommandEntity existing) {
        return existing.getLockedUntil() != null && existing.getLockedUntil().isBefore(OffsetDateTime.now());
    }

    private String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key must not be blank");
        }
        return idempotencyKey.trim();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize idempotent command response", exception);
        }
    }

    private <T> T fromJson(String json, Class<T> responseType) {
        try {
            return objectMapper.readValue(json, responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize idempotent command response", exception);
        }
    }
}
