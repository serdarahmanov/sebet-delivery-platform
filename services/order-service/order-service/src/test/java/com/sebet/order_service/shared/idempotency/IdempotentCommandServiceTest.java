package com.sebet.order_service.shared.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sebet.order_service.internal.dto.response.AssignDriverResponse;
import com.sebet.order_service.persistence.entity.IdempotentCommandEntity;
import com.sebet.order_service.persistence.repository.IdempotentCommandRepository;
import com.sebet.order_service.shared.exception.IdempotencyKeyConflictException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotentCommandServiceTest {

    private final IdempotentCommandRepository repository = mock(IdempotentCommandRepository.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final IdempotentCommandService service = new IdempotentCommandService(repository, objectMapper);

    @Test
    void execute_runsOperationAndStoresResponseWhenKeyIsNew() {
        String orderId = UUID.randomUUID().toString();
        when(repository.findByActionAndIdempotencyKey("INTERNAL_ASSIGN_DRIVER", "idem-1"))
                .thenReturn(Optional.empty());

        AssignDriverResponse response = service.execute(
                "INTERNAL_ASSIGN_DRIVER",
                " idem-1 ",
                orderId,
                "orderId=" + orderId + ";driverId=driver-1",
                AssignDriverResponse.class,
                () -> new AssignDriverResponse(orderId, "driver-1", "2026-07-09T10:00:00Z")
        );

        assertThat(response.driverId()).isEqualTo("driver-1");

        ArgumentCaptor<IdempotentCommandEntity> captor = ArgumentCaptor.forClass(IdempotentCommandEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(captor.getValue().getAction()).isEqualTo("INTERNAL_ASSIGN_DRIVER");
        assertThat(captor.getValue().getOrderId()).isEqualTo(orderId);
        assertThat(captor.getValue().getRequestHash()).hasSize(64);
        assertThat(captor.getValue().getResponseJson()).contains("\"driverId\":\"driver-1\"");
    }

    @Test
    void execute_replaysStoredResponseWithoutRunningOperation() throws Exception {
        String orderId = UUID.randomUUID().toString();
        String fingerprint = "orderId=" + orderId + ";driverId=driver-1";
        AssignDriverResponse stored = new AssignDriverResponse(orderId, "driver-1", "2026-07-09T10:00:00Z");
        IdempotentCommandEntity entity = IdempotentCommandEntity.builder()
                .idempotencyKey("idem-1")
                .action("INTERNAL_ASSIGN_DRIVER")
                .orderId(orderId)
                .requestHash(hash(fingerprint))
                .responseJson(objectMapper.writeValueAsString(stored))
                .build();
        AtomicInteger operationCalls = new AtomicInteger();
        when(repository.findByActionAndIdempotencyKey("INTERNAL_ASSIGN_DRIVER", "idem-1"))
                .thenReturn(Optional.of(entity));

        AssignDriverResponse response = service.execute(
                "INTERNAL_ASSIGN_DRIVER",
                "idem-1",
                orderId,
                fingerprint,
                AssignDriverResponse.class,
                () -> {
                    operationCalls.incrementAndGet();
                    return new AssignDriverResponse(orderId, "other-driver", null);
                }
        );

        assertThat(response).isEqualTo(stored);
        assertThat(operationCalls).hasValue(0);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void execute_rejectsSameKeyWithDifferentRequestFingerprint() {
        String orderId = UUID.randomUUID().toString();
        IdempotentCommandEntity entity = IdempotentCommandEntity.builder()
                .idempotencyKey("idem-1")
                .action("INTERNAL_ASSIGN_DRIVER")
                .orderId(orderId)
                .requestHash(hash("orderId=" + orderId + ";driverId=driver-1"))
                .responseJson("{}")
                .build();
        when(repository.findByActionAndIdempotencyKey("INTERNAL_ASSIGN_DRIVER", "idem-1"))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.execute(
                "INTERNAL_ASSIGN_DRIVER",
                "idem-1",
                orderId,
                "orderId=" + orderId + ";driverId=driver-2",
                AssignDriverResponse.class,
                () -> new AssignDriverResponse(orderId, "driver-2", null)
        )).isInstanceOf(IdempotencyKeyConflictException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void execute_rejectsBlankIdempotencyKey() {
        assertThatThrownBy(() -> service.execute(
                "INTERNAL_ASSIGN_DRIVER",
                " ",
                UUID.randomUUID().toString(),
                "fingerprint",
                AssignDriverResponse.class,
                () -> null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    private String hash(String value) {
        return serviceHash(value);
    }

    private String serviceHash(String value) {
        return java.util.HexFormat.of().formatHex(sha256(value));
    }

    private byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
