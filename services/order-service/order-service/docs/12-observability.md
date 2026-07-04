# Observability

## Current State

Spring Boot Actuator dependency is present, but detailed management endpoint exposure, custom metrics, structured logging, tracing, dashboards, and alerting are not configured in this service yet.

## Metrics To Add

Recommended metrics:

- order creation attempts
- order creation duplicates
- status transitions by from/to status
- cancellation counts by reason
- proposal created/resolved/expired counts
- Redis cache hits/misses
- Kafka consumer failures
- Kafka event processing duration
- WebSocket push success/failure
- verification code generation and verification counts

## Logging To Add

Use structured logs for:

- checkout event consumption
- order id and cart id
- status transitions
- cancellation reason
- proposal lifecycle
- verification code lifecycle without logging the code itself
- Redis lock acquisition/release
- Kafka publish failures

## Tracing To Add

Trace boundaries should include:

- Kafka consumer handling
- database writes
- Redis cache writes
- REST request handling
- WebSocket publish paths

## Known Gap

There is no current observability stack configuration for this service.
