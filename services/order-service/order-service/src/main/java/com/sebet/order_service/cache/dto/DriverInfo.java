package com.sebet.order_service.cache.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Static driver profile stored in the order snapshot (Cache 2).
 * GPS coordinates are intentionally excluded — they live in RedisOrderTracking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DriverInfo {

    private String driverId;
    private String name;
    private String phone;
    private double rating;
    /** e.g. "Toyota Corolla" */
    private String vehicle;
    /** e.g. "BG 4821 AB" */
    private String plateNumber;
}
