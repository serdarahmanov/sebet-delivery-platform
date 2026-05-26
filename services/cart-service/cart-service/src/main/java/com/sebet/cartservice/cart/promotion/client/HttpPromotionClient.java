package com.sebet.cartservice.cart.promotion.client;

import com.sebet.cartservice.cart.metrics.CartMetrics;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.request.PromotionEvaluationRequest;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.DegradedReason;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromotionEvaluationResponse;
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
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class HttpPromotionClient implements PromotionClient {

    // Reactor-level timeout: fires at 500 ms, before the 2 s Netty backstop.
    // Circuit breaker slow-call threshold sits at 300 ms, so calls that
    // complete between 300–500 ms are still counted as slow by Resilience4j.
    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final WebClient promotionWebClient;
    private final CircuitBreaker circuitBreaker;
    private final CartMetrics cartMetrics;

    public HttpPromotionClient(
            @Qualifier("promotionWebClient") WebClient promotionWebClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            CartMetrics cartMetrics
    ) {
        this.promotionWebClient = promotionWebClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("promotionService");
        this.cartMetrics = cartMetrics;
    }

    @Override
    public PromotionEvaluationResponse evaluatePromotions(PromotionEvaluationRequest request) {
        Timer.Sample sample = cartMetrics.startPromotionCallTimer();
        try {
            return circuitBreaker.executeSupplier(() -> doCall(request, sample));
        } catch (Throwable t) {
            return handleFailure(request, t, sample);
        }
    }

    private PromotionEvaluationResponse doCall(PromotionEvaluationRequest request, Timer.Sample sample) {
        PromotionEvaluationResponse response = promotionWebClient
                .post()
                .uri("/internal/promotions/validate")
                .headers(h -> { String rid = MDC.get("requestId"); if (rid != null) h.set("X-Request-Id", rid); })
                .bodyValue(request)
                .retrieve()
                .bodyToMono(PromotionEvaluationResponse.class)
                .timeout(TIMEOUT)
                .block();

        if (response == null) {
            log.warn("Promotion service returned a null body for cartId={}. Treating as degraded.", request.cartId());
            cartMetrics.stopPromotionCallTimer(sample, "null_response");
            cartMetrics.recordPromotionServiceDegraded(DegradedReason.NULL_RESPONSE.name().toLowerCase());
            return PromotionEvaluationResponse.degraded(request.cartId(), DegradedReason.NULL_RESPONSE);
        }
        cartMetrics.stopPromotionCallTimer(sample, "success");
        return response;
    }

    private PromotionEvaluationResponse handleFailure(PromotionEvaluationRequest request, Throwable t, Timer.Sample sample) {
        DegradedReason reason = classify(t);
        cartMetrics.stopPromotionCallTimer(sample, reason.name().toLowerCase());
        cartMetrics.recordPromotionServiceDegraded(reason.name().toLowerCase());
        log.warn("Promotion service unavailable, cartId={}, reason: {}, degradedReason: {}",
                request.cartId(), t.getMessage(), reason);
        return PromotionEvaluationResponse.degraded(request.cartId(), reason);
    }

    private DegradedReason classify(Throwable t) {
        if (t instanceof CallNotPermittedException) {
            return DegradedReason.CIRCUIT_OPEN;
        }
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof TimeoutException) {
                return DegradedReason.TIMEOUT;
            }
            cause = cause.getCause();
        }
        return DegradedReason.ERROR;
    }
}
