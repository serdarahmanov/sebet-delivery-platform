package com.sebet.cartservice.cart.config;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the OTel Java Agent's globally registered {@link OpenTelemetry} instance
 * as a Spring bean.
 *
 * <p>Why this is needed:
 * Spring Boot's {@code OtelAutoConfiguration} (pulled in transitively by
 * {@code micrometer-tracing-bridge-otel}) requires an {@code OpenTelemetry} bean in the
 * application context to build a {@code Tracer}. That {@code Tracer} is then wired into
 * Micrometer's {@code ObservationRegistry}, which is what attaches the current
 * {@code trace_id} and {@code span_id} to histogram/timer samples as Prometheus exemplars.
 *
 * <p>Without this bean, Spring Boot falls back to a no-op {@code OpenTelemetry} instance:
 * the bridge loads, but every exemplar it tries to attach carries empty IDs — making the
 * Prometheus → Tempo click-through silently non-functional.
 *
 * <p>The OTel Java Agent calls {@code GlobalOpenTelemetry.set()} during JVM startup,
 * before the Spring context is refreshed, so the value returned here is already the
 * fully configured agent instance (sampler, exporter, propagators all active).
 *
 * <p>{@code @ConditionalOnMissingBean} makes this safe in two edge cases:
 * <ul>
 *   <li>Test contexts — a test-double {@code OpenTelemetry} can be provided without conflict.</li>
 *   <li>Future Spring Boot versions — if Boot ever auto-registers this bean, ours steps aside.</li>
 * </ul>
 */
@Configuration
public class OtelBridgeConfig {

    @Bean
    @ConditionalOnMissingBean(OpenTelemetry.class)
    public OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }
}
