# Observability

## Metrics

Micrometer exposes Prometheus metrics at:

```text
/actuator/prometheus
```

Key metric areas:

- cart item mutations
- basket clearing
- promo claims
- checkout initiation and confirmation
- checkout rejection and timeout
- delivery calls
- promotion calls
- response cache hits and misses
- Redis schema migration
- Kafka DLT publish success/failure

## Tracing

The Docker image bundles the OpenTelemetry Java agent:

```text
otel/opentelemetry-javaagent-2.9.0.jar
```

The agent is activated through `JAVA_TOOL_OPTIONS`.

The agent auto-instruments:

- Spring MVC HTTP requests.
- WebClient calls to promotion-service and delivery-service.
- Kafka consumers and producers.
- JDBC calls to PostgreSQL.
- Redis operations.

Outbound WebClient calls receive W3C trace context propagation automatically through the agent.

Trace pipeline:

```text
cart-service
  -> OpenTelemetry Collector
  -> Tempo
  -> Grafana
```

## Logging

Structured logging is configured through `logback-spring.xml`.

MDC contexts:

- HTTP requests: request id, user id, method, path.
- Kafka records: request id, topic, partition, offset, key.
- Checkout async threads: copied MDC from submitting thread.

The OTel agent can inject `traceId` and `spanId` into MDC when spans are active.

Non-prod profiles use colored text logs. The `prod` profile uses JSON logs through `LogstashEncoder`.

HTTP clients forward `requestId` as `X-Request-Id` when it is present in MDC.

## Local Observability Stack

`docker-compose.yaml` starts:

- OTel Collector on `4317` and `4318`
- Tempo on `3200`
- Prometheus on `9090`
- Grafana on `3000`

Grafana datasources and dashboards are provisioned from:

```text
docker/grafana/provisioning
docker/grafana/dashboards
```

## Known Metric Gap

Projection staleness is not currently exposed as a health indicator or metric, even though projection tables carry update timestamps.

Add a health indicator or metric if production operations need freshness visibility for product, inventory, and store projections.
