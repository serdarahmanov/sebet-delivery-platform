package com.sebet.cartservice.cart.exception;

import com.sebet.cartservice.cart.metrics.CartMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final CartMetrics cartMetrics;

    public GlobalExceptionHandler(CartMetrics cartMetrics) {
        this.cartMetrics = cartMetrics;
    }

    /**
     * Intercepts all CartVersionConflictException throws (13 call sites across
     * CartService and CheckoutService) in one place.
     *
     * Before this handler existed, Spring MVC translated the exception to a 409
     * automatically via ResponseStatusException — but silently, with no metric.
     * The response body is identical to what Spring produced before (ProblemDetail
     * is Spring Boot 4.x's default error format for ResponseStatusException).
     */
    @ExceptionHandler(CartVersionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleCasConflict(CartVersionConflictException ex) {
        cartMetrics.recordCasConflict();
        log.warn("cas_conflict — cart modified concurrently, returning 409. reason={}", ex.getReason());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getReason());
    }
}
