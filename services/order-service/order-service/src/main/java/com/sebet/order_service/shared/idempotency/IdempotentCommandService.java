package com.sebet.order_service.shared.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.persistence.entity.IdempotentCommandEntity;
import com.sebet.order_service.persistence.repository.IdempotentCommandRepository;
import com.sebet.order_service.shared.exception.IdempotencyKeyConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class IdempotentCommandService {

    private final IdempotentCommandRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
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

        return repository.findByActionAndIdempotencyKey(action, normalizedKey)
                .map(existing -> replay(existing, action, requestHash, responseType))
                .orElseGet(() -> runAndRecord(action, normalizedKey, orderId, requestHash, responseType, operation));
    }

    private <T> T runAndRecord(
            String action,
            String idempotencyKey,
            String orderId,
            String requestHash,
            Class<T> responseType,
            Supplier<T> operation
    ) {
        T response = operation.get();
        repository.saveAndFlush(IdempotentCommandEntity.builder()
                .idempotencyKey(idempotencyKey)
                .action(action)
                .orderId(orderId)
                .requestHash(requestHash)
                .responseJson(toJson(response))
                .build());
        return response;
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
        return fromJson(existing.getResponseJson(), responseType);
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
