package com.sebet.order_service.shared.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sebet.order_service.internal.dto.response.AssignDriverResponse;
import com.sebet.order_service.persistence.entity.IdempotentCommandEntity;
import com.sebet.order_service.persistence.repository.IdempotentCommandRepository;
import com.sebet.order_service.shared.exception.IdempotencyKeyConflictException;
import com.sebet.order_service.shared.exception.IdempotencyRequestInProgressException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotentCommandServiceTest {

    private final IdempotentCommandRepository repository = mock(IdempotentCommandRepository.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final PlatformTransactionManager transactionManager = new NoOpTransactionManager();
    private final IdempotentCommandService service = new IdempotentCommandService(
            repository,
            objectMapper,
            transactionManager,
            Duration.ofMinutes(2),
            "test-instance"
    );

    @BeforeEach
    void resetMocks() {
        reset(repository);
    }

    @Test
    void execute_runsOperationAndStoresResponseWhenKeyIsNew() {
        String orderId = UUID.randomUUID().toString();
        IdempotentCommandEntity[] reservation = new IdempotentCommandEntity[1];
        List<String> savedStatuses = new ArrayList<>();
        when(repository.saveAndFlush(any(IdempotentCommandEntity.class)))
                .thenAnswer(invocation -> {
                    IdempotentCommandEntity entity = invocation.getArgument(0);
                    savedStatuses.add(entity.getStatus());
                    if ("IN_PROGRESS".equals(entity.getStatus())) {
                        reservation[0] = entity;
                    }
                    return entity;
                });
        when(repository.findByActionAndIdempotencyKey("INTERNAL_ASSIGN_DRIVER", "idem-1"))
                .thenAnswer(invocation -> Optional.of(reservation[0]));

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
        verify(repository, org.mockito.Mockito.times(2)).saveAndFlush(captor.capture());
        assertThat(savedStatuses).containsExactly("IN_PROGRESS", "COMPLETED");
        assertThat(captor.getAllValues().get(1).getResponseJson()).contains("\"driverId\":\"driver-1\"");
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
                .status("COMPLETED")
                .build();
        when(repository.saveAndFlush(any(IdempotentCommandEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
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
                .status("COMPLETED")
                .build();
        when(repository.saveAndFlush(any(IdempotentCommandEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
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
    }

    @Test
    void execute_rejectsRetryWhenExistingRequestIsStillInProgress() {
        String orderId = UUID.randomUUID().toString();
        String fingerprint = "orderId=" + orderId + ";driverId=driver-1";
        IdempotentCommandEntity entity = IdempotentCommandEntity.builder()
                .idempotencyKey("idem-1")
                .action("INTERNAL_ASSIGN_DRIVER")
                .orderId(orderId)
                .requestHash(hash(fingerprint))
                .status("IN_PROGRESS")
                .lockedBy("other-instance:owner")
                .lockedUntil(OffsetDateTime.now().plusMinutes(1))
                .build();
        when(repository.saveAndFlush(any(IdempotentCommandEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(repository.findByActionAndIdempotencyKey("INTERNAL_ASSIGN_DRIVER", "idem-1"))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.execute(
                "INTERNAL_ASSIGN_DRIVER",
                "idem-1",
                orderId,
                fingerprint,
                AssignDriverResponse.class,
                () -> new AssignDriverResponse(orderId, "driver-1", null)
        )).isInstanceOf(IdempotencyRequestInProgressException.class);
    }

    @Test
    void execute_reclaimsExpiredInProgressReservationAndCompletesOperation() {
        String orderId = UUID.randomUUID().toString();
        String fingerprint = "orderId=" + orderId + ";driverId=driver-1";
        IdempotentCommandEntity entity = IdempotentCommandEntity.builder()
                .idempotencyKey("idem-1")
                .action("INTERNAL_ASSIGN_DRIVER")
                .orderId(orderId)
                .requestHash(hash(fingerprint))
                .status("IN_PROGRESS")
                .lockedBy("dead-instance:owner")
                .lockedUntil(OffsetDateTime.now().minusMinutes(1))
                .build();
        when(repository.saveAndFlush(any(IdempotentCommandEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findByActionAndIdempotencyKey("INTERNAL_ASSIGN_DRIVER", "idem-1"))
                .thenReturn(Optional.of(entity));
        when(repository.reclaimExpiredInProgress(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        AssignDriverResponse response = service.execute(
                "INTERNAL_ASSIGN_DRIVER",
                "idem-1",
                orderId,
                fingerprint,
                AssignDriverResponse.class,
                () -> new AssignDriverResponse(orderId, "driver-1", null)
        );

        assertThat(response.driverId()).isEqualTo("driver-1");
        assertThat(entity.getStatus()).isEqualTo("COMPLETED");
        assertThat(entity.getResponseJson()).contains("\"driverId\":\"driver-1\"");
    }

    @Test
    void execute_releasesReservationWhenOperationFails() {
        String orderId = UUID.randomUUID().toString();
        IdempotentCommandEntity[] reservation = new IdempotentCommandEntity[1];
        when(repository.saveAndFlush(any(IdempotentCommandEntity.class)))
                .thenAnswer(invocation -> {
                    reservation[0] = invocation.getArgument(0);
                    return reservation[0];
                });

        assertThatThrownBy(() -> service.execute(
                "INTERNAL_ASSIGN_DRIVER",
                "idem-1",
                orderId,
                "orderId=" + orderId + ";driverId=driver-1",
                AssignDriverResponse.class,
                () -> {
                    throw new IllegalStateException("boom");
                }
        )).isInstanceOf(IllegalStateException.class);

        verify(repository).deleteByActionAndIdempotencyKeyAndRequestHashAndStatusAndLockedBy(
                org.mockito.Mockito.eq("INTERNAL_ASSIGN_DRIVER"),
                org.mockito.Mockito.eq("idem-1"),
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.eq("IN_PROGRESS"),
                org.mockito.Mockito.eq(reservation[0].getLockedBy())
        );
    }

    private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
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
