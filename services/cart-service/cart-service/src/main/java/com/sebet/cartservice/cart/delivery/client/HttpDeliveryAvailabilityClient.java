package com.sebet.cartservice.cart.delivery.client;

import com.sebet.cartservice.cart.delivery.dto.DeliveryAvailabilityRequest;
import com.sebet.cartservice.cart.delivery.dto.DeliveryAvailabilityResponse;
import com.sebet.cartservice.cart.delivery.dto.DeliveryCheckoutQuoteRequest;
import com.sebet.cartservice.cart.delivery.dto.DeliveryCheckoutQuoteResponse;
import com.sebet.cartservice.cart.delivery.dto.DeliveryFeeQuoteRequest;
import com.sebet.cartservice.cart.delivery.dto.DeliveryFeeQuoteResponse;
import com.sebet.cartservice.cart.delivery.dto.DeliveryScheduleQuoteRequest;
import com.sebet.cartservice.cart.delivery.dto.DeliveryScheduleQuoteResponse;
import com.sebet.cartservice.cart.metrics.CartMetrics;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class HttpDeliveryAvailabilityClient implements DeliveryAvailabilityClient {

    // Reactor-level timeout: fires at 600 ms, before the 3 s Netty backstop.
    // Circuit breaker slow-call threshold sits at 400 ms, so calls that
    // complete between 400–600 ms are still counted as slow by Resilience4j.
    private static final Duration TIMEOUT = Duration.ofMillis(600);

    private final WebClient deliveryWebClient;
    private final CircuitBreaker circuitBreaker;
    private final CartMetrics cartMetrics;

    public HttpDeliveryAvailabilityClient(
            @Qualifier("deliveryWebClient") WebClient deliveryWebClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            CartMetrics cartMetrics
    ) {
        this.deliveryWebClient = deliveryWebClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("deliveryService");
        this.cartMetrics = cartMetrics;
    }

    // ── checkAvailability ─────────────────────────────────────────────────────

    @Override
    public DeliveryAvailabilityResponse checkAvailability(DeliveryAvailabilityRequest request) {
        Timer.Sample sample = cartMetrics.startDeliveryCallTimer();
        try {
            return circuitBreaker.executeSupplier(() -> doCheckAvailability(request, sample));
        } catch (Throwable t) {
            String outcome = classify(t);
            cartMetrics.stopDeliveryCallTimer(sample, outcome, "availability");
            cartMetrics.recordDeliveryServiceDegraded(outcome, "availability");
            log.warn("Delivery service unavailable, addressId={}, reason: {}", request.addressId(), t.getMessage());
            return degradedAvailabilityResponse(request.addressId(), "Delivery service unavailable");
        }
    }

    private DeliveryAvailabilityResponse doCheckAvailability(DeliveryAvailabilityRequest request, Timer.Sample sample) {
        DeliveryAvailabilityResponse response = deliveryWebClient
                .post()
                .uri("/delivery/availability")
                .headers(h -> { String rid = MDC.get("requestId"); if (rid != null) h.set("X-Request-Id", rid); })
                .bodyValue(request)
                .retrieve()
                .bodyToMono(DeliveryAvailabilityResponse.class)
                .timeout(TIMEOUT)
                .block();

        if (response == null) {
            cartMetrics.stopDeliveryCallTimer(sample, "null_response", "availability");
            cartMetrics.recordDeliveryServiceDegraded("null_response", "availability");
            return degradedAvailabilityResponse(request.addressId(), "Empty response from delivery service");
        }
        cartMetrics.stopDeliveryCallTimer(sample, "success", "availability");
        return response;
    }

    // ── getFeeQuote ───────────────────────────────────────────────────────────

    @Override
    public DeliveryFeeQuoteResponse getFeeQuote(DeliveryFeeQuoteRequest request) {
        Timer.Sample sample = cartMetrics.startDeliveryCallTimer();
        try {
            return circuitBreaker.executeSupplier(() -> doGetFeeQuote(request, sample));
        } catch (Throwable t) {
            String outcome = classify(t);
            cartMetrics.stopDeliveryCallTimer(sample, outcome, "fee_quote");
            cartMetrics.recordDeliveryServiceDegraded(outcome, "fee_quote");
            log.warn("Delivery service unavailable for fee quotes, baskets={}, reason: {}",
                    request.baskets().size(), t.getMessage());
            return new DeliveryFeeQuoteResponse(List.of());
        }
    }

    private DeliveryFeeQuoteResponse doGetFeeQuote(DeliveryFeeQuoteRequest request, Timer.Sample sample) {
        DeliveryFeeQuoteResponse response = deliveryWebClient
                .post()
                .uri("/delivery/fee/quote")
                .headers(h -> { String rid = MDC.get("requestId"); if (rid != null) h.set("X-Request-Id", rid); })
                .bodyValue(request)
                .retrieve()
                .bodyToMono(DeliveryFeeQuoteResponse.class)
                .timeout(TIMEOUT)
                .block();

        if (response == null) {
            log.warn("Delivery service returned null fee quote response for {} baskets", request.baskets().size());
            cartMetrics.stopDeliveryCallTimer(sample, "null_response", "fee_quote");
            cartMetrics.recordDeliveryServiceDegraded("null_response", "fee_quote");
            return new DeliveryFeeQuoteResponse(List.of());
        }
        cartMetrics.stopDeliveryCallTimer(sample, "success", "fee_quote");
        return response;
    }

    // ── getScheduledQuote ─────────────────────────────────────────────────────

    @Override
    public DeliveryScheduleQuoteResponse getScheduledQuote(DeliveryScheduleQuoteRequest request) {
        Timer.Sample sample = cartMetrics.startDeliveryCallTimer();
        try {
            return circuitBreaker.executeSupplier(() -> doGetScheduledQuote(request, sample));
        } catch (Throwable t) {
            String outcome = classify(t);
            cartMetrics.stopDeliveryCallTimer(sample, outcome, "scheduled_quote");
            cartMetrics.recordDeliveryServiceDegraded(outcome, "scheduled_quote");
            log.warn("Delivery service unavailable for scheduled quote, storeId={}, reason: {}",
                    request.storeId(), t.getMessage());
            return new DeliveryScheduleQuoteResponse(null, null, 0, null, "Delivery service unavailable");
        }
    }

    private DeliveryScheduleQuoteResponse doGetScheduledQuote(DeliveryScheduleQuoteRequest request, Timer.Sample sample) {
        DeliveryScheduleQuoteResponse response = deliveryWebClient
                .post()
                .uri("/delivery/schedule/quote")
                .headers(h -> { String rid = MDC.get("requestId"); if (rid != null) h.set("X-Request-Id", rid); })
                .bodyValue(request)
                .retrieve()
                .bodyToMono(DeliveryScheduleQuoteResponse.class)
                .timeout(TIMEOUT)
                .block();

        if (response == null) {
            log.warn("Delivery service returned null scheduled quote for storeId={}", request.storeId());
            cartMetrics.stopDeliveryCallTimer(sample, "null_response", "scheduled_quote");
            cartMetrics.recordDeliveryServiceDegraded("null_response", "scheduled_quote");
            return new DeliveryScheduleQuoteResponse(null, null, 0, null, "Empty response from delivery service");
        }
        cartMetrics.stopDeliveryCallTimer(sample, "success", "scheduled_quote");
        return response;
    }

    // ── getCheckoutQuote ──────────────────────────────────────────────────────

    @Override
    public DeliveryCheckoutQuoteResponse getCheckoutQuote(DeliveryCheckoutQuoteRequest request) {
        Timer.Sample sample = cartMetrics.startDeliveryCallTimer();
        try {
            return circuitBreaker.executeSupplier(() -> doGetCheckoutQuote(request, sample));
        } catch (Throwable t) {
            String outcome = classify(t);
            cartMetrics.stopDeliveryCallTimer(sample, outcome, "checkout_quote");
            cartMetrics.recordDeliveryServiceDegraded(outcome, "checkout_quote");
            log.warn("Delivery service unavailable for checkout quote, baskets={}, reason: {}",
                    request.baskets().size(), t.getMessage());
            return new DeliveryCheckoutQuoteResponse(List.of());
        }
    }

    private DeliveryCheckoutQuoteResponse doGetCheckoutQuote(
            DeliveryCheckoutQuoteRequest request, Timer.Sample sample) {
        DeliveryCheckoutQuoteResponse response = deliveryWebClient
                .post()
                .uri("/delivery/checkout/quote")
                .headers(h -> { String rid = MDC.get("requestId"); if (rid != null) h.set("X-Request-Id", rid); })
                .bodyValue(request)
                .retrieve()
                .bodyToMono(DeliveryCheckoutQuoteResponse.class)
                .timeout(TIMEOUT)
                .block();

        if (response == null) {
            cartMetrics.stopDeliveryCallTimer(sample, "null_response", "checkout_quote");
            cartMetrics.recordDeliveryServiceDegraded("null_response", "checkout_quote");
            return new DeliveryCheckoutQuoteResponse(List.of());
        }
        cartMetrics.stopDeliveryCallTimer(sample, "success", "checkout_quote");
        return response;
    }

    // ── shared helpers ────────────────────────────────────────────────────────

    private DeliveryAvailabilityResponse degradedAvailabilityResponse(String addressId, String reason) {
        return new DeliveryAvailabilityResponse(addressId, false, null, null, List.of(), reason);
    }

    private String classify(Throwable t) {
        if (t instanceof CallNotPermittedException) {
            return "circuit_open";
        }
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof TimeoutException) {
                return "timeout";
            }
            cause = cause.getCause();
        }
        return "error";
    }
}
