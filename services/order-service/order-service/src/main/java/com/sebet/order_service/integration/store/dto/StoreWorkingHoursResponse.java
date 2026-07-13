package com.sebet.order_service.integration.store.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

/**
 * Working hours returned by the store service for a given store.
 * Used to validate that a scheduled delivery window falls within operational hours.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoreWorkingHoursResponse {

    private LocalTime openTime;
    private LocalTime closeTime;
    private Set<DayOfWeek> workingDays;
}
