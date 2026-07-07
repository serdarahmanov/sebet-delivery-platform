package com.sebet.order_service.driver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.VerificationCodeCacheDto;
import com.sebet.order_service.cache.repository.VerificationCodeRedisRepository;
import com.sebet.order_service.driver.dto.response.DriverArriveResponse;
import com.sebet.order_service.driver.dto.response.DriverCompleteDeliveryResponse;
import com.sebet.order_service.driver.dto.response.DriverPickupResponse;
import com.sebet.order_service.order.service.OrderLifecycleResult;
import com.sebet.order_service.order.service.OrderLifecycleService;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import com.sebet.order_service.shared.exception.VerificationCodeNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class DriverOrderLifecycleService {

    private final OrderLifecycleService orderLifecycleService;
    private final VerificationCodeRedisRepository verificationCodeRedisRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final ObjectMapper objectMapper;

    public DriverPickupResponse confirmPickup(String driverId, String orderId) {
        OrderLifecycleResult result = orderLifecycleService.driverPickup(orderId, driverId);
        return new DriverPickupResponse(orderId, result.newStatus().name());
    }

    public DriverArriveResponse markArrived(String driverId, String orderId) {
        String code = generateVerificationCode();
        String metadataJson = serializeCodeMetadata(code);

        OrderLifecycleResult result = orderLifecycleService.driverArrive(orderId, driverId, metadataJson);

        verificationCodeRedisRepository.save(orderId, VerificationCodeCacheDto.builder()
                .code(code)
                .generatedAt(result.changedAt().toString())
                .build());

        return new DriverArriveResponse(orderId, result.newStatus().name());
    }

    public DriverCompleteDeliveryResponse completeDelivery(String driverId, String orderId, String verificationCode) {
        String storedCode = resolveVerificationCode(orderId);

        if (!storedCode.equals(verificationCode)) {
            throw new IllegalArgumentException("Verification code does not match");
        }

        OrderLifecycleResult result = orderLifecycleService.driverComplete(orderId, driverId);
        return new DriverCompleteDeliveryResponse(
                orderId,
                result.newStatus().name(),
                result.changedAt().toString()
        );
    }

    private String resolveVerificationCode(String orderId) {
        return verificationCodeRedisRepository.findById(orderId)
                .map(VerificationCodeCacheDto::getCode)
                .orElseGet(() -> resolveVerificationCodeFromDb(orderId));
    }

    private String resolveVerificationCodeFromDb(String orderId) {
        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException e) {
            throw new OrderNotFoundException(orderId);
        }

        return orderStatusHistoryRepository
                .findFirstByOrderIdAndToStatus(id, OrderStatus.ARRIVED)
                .map(this::extractCodeFromMetadata)
                .orElseThrow(() -> new VerificationCodeNotFoundException(orderId));
    }

    private String extractCodeFromMetadata(OrderStatusHistoryEntity history) {
        if (history.getMetadataJson() == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(history.getMetadataJson());
            JsonNode codeNode = node.get("code");
            return codeNode != null ? codeNode.asText() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String generateVerificationCode() {
        return String.format("%02d", ThreadLocalRandom.current().nextInt(100));
    }

    private String serializeCodeMetadata(String code) {
        try {
            return objectMapper.writeValueAsString(new CodeMetadata(code));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize verification code metadata", e);
        }
    }

    private record CodeMetadata(String code) {}
}
