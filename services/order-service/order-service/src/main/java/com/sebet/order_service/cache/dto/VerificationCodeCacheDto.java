package com.sebet.order_service.cache.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cache 7 — order:verification:{orderId}  (STRING / JSON)
 *
 * Stores the 2-digit delivery verification code generated when
 * the driver arrives at the customer's address.
 *
 * Lifecycle:
 *   Written by: OrderArrivedEventConsumer (Kafka) — TODO: implement
 *   Read by:    GET /api/v1/orders/{orderId}/verification-code
 *               GET /api/v1/orders/active/{orderId}  (merged into ActiveOrderDetailResponse)
 *   Cleared by: order completion or expiry
 *
 * TTL: 30 minutes — if the driver has not verified within 30 minutes
 * of arriving, this is a support case, not a normal flow.
 *
 * {@code verifiedAt} is null until the driver submits the correct code.
 * Written by: POST /api/v1/driver/orders/{orderId}/verify-code — TODO: implement
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VerificationCodeCacheDto {

    /**
     * Zero-padded 2-digit code shown to the customer.
     * Range "00"–"99".  Always a 2-character string so "04" is never
     * serialised as the integer 4.
     */
    private String code;

    /** ISO-8601 timestamp of when the code was generated. */
    private String generatedAt;

    /**
     * ISO-8601 timestamp of when the driver successfully submitted the code.
     * Null until verified.
     */
    private String verifiedAt;
}
