package com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response;

public enum DegradedReason {
    /** The HTTP call to the promotion-service exceeded the configured timeout */
    TIMEOUT,
    /** The Resilience4j circuit breaker was OPEN and short-circuited the call */
    CIRCUIT_OPEN,
    /** Any other exception (connection refused, 5xx, deserialization failure, etc.) */
    ERROR,
    /**
     * The promotion-service returned a 2xx with an empty body — no exception,
     * no fallback triggered, but no promotion data was received.
     * Points to a bug or misconfiguration in the promotion-service.
     */
    NULL_RESPONSE,
    /**
     * A PromotionClient implementation returned null to its caller despite the
     * null guard that exists inside HttpPromotionClient. Points to a contract
     * violation inside cart-service code, not in the promotion-service.
     */
    INTERNAL
}
