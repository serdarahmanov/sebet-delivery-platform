package com.sebet.order_service.integration.store;

import com.sebet.order_service.integration.store.dto.StoreWorkingHoursResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HTTP client for the store service.
 *
 * Fetches store working hours with up to {@code MAX_RETRIES} attempts.
 * If the store service is unreachable after all retries, falls back to
 * the default working hours configured via environment variables.
 *
 * Retry implementation is intentionally simple (blocking loop) — a proper
 * resilience solution (e.g. Resilience4j) will replace this when wired up.
 */
@Slf4j
@Component
public class StoreServiceClient {

    private static final int MAX_RETRIES = 3;

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final StoreWorkingHoursResponse fallback;

    public StoreServiceClient(
            RestTemplate restTemplate,
            @Value("${order-service.store-service.base-url}") String baseUrl,
            @Value("${order-service.store-service.fallback-open-time:08:00}") String fallbackOpenTime,
            @Value("${order-service.store-service.fallback-close-time:19:00}") String fallbackCloseTime,
            @Value("${order-service.store-service.fallback-working-days:MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY}") String fallbackWorkingDays
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.fallback = buildFallback(fallbackOpenTime, fallbackCloseTime, fallbackWorkingDays);
    }

    /**
     * Returns working hours for the given store.
     * Falls back to default config if the store service is unavailable after retries.
     */
    public StoreWorkingHoursResponse getWorkingHours(String storeId) {
        String url = baseUrl + "/api/v1/stores/" + storeId + "/working-hours";
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                StoreWorkingHoursResponse response =
                        restTemplate.getForObject(url, StoreWorkingHoursResponse.class);
                if (response != null) {
                    return response;
                }
            } catch (Exception e) {
                lastException = e;
                log.warn("Store service call failed for storeId={} attempt={}/{}", storeId, attempt, MAX_RETRIES, e);
            }
        }

        log.error("Store service unavailable for storeId={} after {} retries — using fallback working hours",
                storeId, MAX_RETRIES, lastException);
        return fallback;
    }

    private StoreWorkingHoursResponse buildFallback(
            String openTime,
            String closeTime,
            String workingDays
    ) {
        try {
            Set<DayOfWeek> days = Arrays.stream(workingDays.split(","))
                    .map(String::trim)
                    .map(d -> DayOfWeek.valueOf(d.toUpperCase()))
                    .collect(Collectors.toSet());
            return StoreWorkingHoursResponse.builder()
                    .openTime(LocalTime.parse(openTime))
                    .closeTime(LocalTime.parse(closeTime))
                    .workingDays(days)
                    .build();
        } catch (DateTimeParseException | IllegalArgumentException e) {
            log.error("Invalid store-service fallback config — defaulting to 08:00-19:00 all days", e);
            return StoreWorkingHoursResponse.builder()
                    .openTime(LocalTime.of(8, 0))
                    .closeTime(LocalTime.of(19, 0))
                    .workingDays(Set.of(DayOfWeek.values()))
                    .build();
        }
    }
}
