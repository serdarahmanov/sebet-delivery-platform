package com.sebet.order_service.shared.idempotency;

import com.sebet.order_service.internal.dto.response.AssignDriverResponse;
import com.sebet.order_service.persistence.entity.IdempotentCommandEntity;
import com.sebet.order_service.persistence.repository.IdempotentCommandRepository;
import com.sebet.order_service.shared.exception.IdempotencyRequestInProgressException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "order-service.idempotency.in-progress-lease=PT2M",
        "order-service.idempotency.cleanup-interval-ms=3600000"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class IdempotentCommandServiceIntegrationTest {

    private static final String ACTION = "INTEGRATION_ASSIGN_DRIVER";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
    );

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")
    ).withExposedPorts(6379);

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka-native:3.8.0")
    );

    @Autowired
    private IdempotentCommandService service;

    @Autowired
    private IdempotentCommandRepository repository;

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("order-service.internal.secret", () -> "test-internal-secret");
    }

    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }

    @Test
    void concurrentSameKeyRetrySeesCommittedInProgressReservationThenReplaysCompletedResponse() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch operationStarted = new CountDownLatch(1);
            CountDownLatch releaseOperation = new CountDownLatch(1);
            AtomicInteger operationCalls = new AtomicInteger();

            String orderId = UUID.randomUUID().toString();
            String key = "idem-" + UUID.randomUUID();
            String fingerprint = "orderId=" + orderId + ";driverId=driver-1";

            Future<AssignDriverResponse> first = executor.submit(() -> service.execute(
                    ACTION,
                    key,
                    orderId,
                    fingerprint,
                    AssignDriverResponse.class,
                    () -> {
                        operationCalls.incrementAndGet();
                        operationStarted.countDown();
                        await(releaseOperation);
                        return new AssignDriverResponse(orderId, "driver-1", "2026-07-09T10:00:00Z");
                    }
            ));

            assertThat(operationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            IdempotentCommandEntity inProgress = repository.findByActionAndIdempotencyKey(ACTION, key).orElseThrow();
            assertThat(inProgress.getStatus()).isEqualTo("IN_PROGRESS");
            assertThat(inProgress.getResponseJson()).isNull();

            assertThatThrownBy(() -> service.execute(
                    ACTION,
                    key,
                    orderId,
                    fingerprint,
                    AssignDriverResponse.class,
                    () -> {
                        operationCalls.incrementAndGet();
                        return new AssignDriverResponse(orderId, "driver-2", null);
                    }
            )).isInstanceOf(IdempotencyRequestInProgressException.class);

            releaseOperation.countDown();
            AssignDriverResponse completed = first.get(10, TimeUnit.SECONDS);
            assertThat(completed.driverId()).isEqualTo("driver-1");
            assertThat(operationCalls).hasValue(1);

            AssignDriverResponse replayed = service.execute(
                    ACTION,
                    key,
                    orderId,
                    fingerprint,
                    AssignDriverResponse.class,
                    () -> {
                        operationCalls.incrementAndGet();
                        return new AssignDriverResponse(orderId, "driver-3", null);
                    }
            );

            assertThat(replayed).isEqualTo(completed);
            assertThat(operationCalls).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredInProgressReservationCanBeReclaimedAndCompleted() {
        String orderId = UUID.randomUUID().toString();
        String key = "expired-" + UUID.randomUUID();
        String fingerprint = "orderId=" + orderId + ";driverId=driver-1";
        String requestHash = java.util.HexFormat.of().formatHex(sha256(fingerprint));

        repository.saveAndFlush(IdempotentCommandEntity.builder()
                .idempotencyKey(key)
                .action(ACTION)
                .orderId(orderId)
                .requestHash(requestHash)
                .status("IN_PROGRESS")
                .lockedBy("dead-instance")
                .lockedUntil(OffsetDateTime.now().minusMinutes(5))
                .build());

        AssignDriverResponse response = service.execute(
                ACTION,
                key,
                orderId,
                fingerprint,
                AssignDriverResponse.class,
                () -> new AssignDriverResponse(orderId, "driver-1", "2026-07-09T10:00:00Z")
        );

        IdempotentCommandEntity completed = repository.findByActionAndIdempotencyKey(ACTION, key).orElseThrow();
        assertThat(response.driverId()).isEqualTo("driver-1");
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getLockedBy()).isNull();
        assertThat(completed.getResponseJson()).contains("\"driverId\": \"driver-1\"");
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
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
