# Integrations

Cart-service integrates with downstream services over HTTP and with platform events over Kafka.

This document covers HTTP integrations. Kafka is covered in [Kafka Events](07-kafka-events.md).

## Promotion Service

Client:

```text
HttpPromotionClient
```

Configured by:

```yaml
services:
  promotion:
    base-url: ...
```

Runtime env in prod:

```text
PROMOTION_SERVICE_URL
```

Called endpoint:

```http
POST /internal/promotions/validate
```

Behavior:

- Uses `WebClient`.
- Reactor timeout: 500 ms.
- Netty backstop timeout: 2 seconds.
- Uses Resilience4j circuit breaker `promotionService`.
- On timeout, circuit-open, null response, or error, returns degraded promotion response.

## Delivery Service

Client:

```text
HttpDeliveryAvailabilityClient
```

Configured by:

```yaml
services:
  delivery:
    base-url: ...
```

Runtime env in prod:

```text
DELIVERY_SERVICE_URL
```

Called endpoints:

```http
POST /delivery/availability
POST /delivery/fee/quote
POST /delivery/schedule/quote
POST /delivery/checkout/quote
```

Behavior:

- Uses `WebClient`.
- Reactor timeout: 600 ms.
- Netty backstop timeout: 3 seconds.
- Uses Resilience4j circuit breaker `deliveryService`.
- On failure, returns unavailable/empty quote style responses so validation can surface blocking delivery issues.

## Request Correlation

HTTP clients propagate `X-Request-Id` from MDC when present.

Distributed trace headers are propagated by the OpenTelemetry Java agent.

## Degraded Behavior

Promotion degradation does not hard-fail cart browsing. It produces degraded promotion validation results.

Delivery degradation usually results in missing delivery fee or unavailable delivery responses. Checkout treats these as blocking issues.

## Local Development Note

`docker-compose.yaml` does not start promotion-service or delivery-service. Run those services separately or expect degraded integration behavior.
