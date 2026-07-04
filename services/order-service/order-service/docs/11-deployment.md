# Deployment

## Current State

This service does not currently include Docker, docker-compose, or Kubernetes manifests.

Spring configuration is YAML-based:

```text
src/main/resources/application.yml
src/main/resources/application-prod.yml
src/test/resources/application-test.yml
```

The default `application.yml` sets the service name and disables JPA Open
Session in View. The `prod` profile reads PostgreSQL, Redis, and Kafka settings
from environment variables. The `test` profile is used by Testcontainers-backed
tests.

## Required Runtime Dependencies

Before complete deployment, the service will need:

- PostgreSQL
- Redis
- Kafka
- Kafka topic configuration
- production profile activation
- health/metrics exposure settings

Kafka checkout topic requirements:

- `ORDER_SERVICE_KAFKA_CHECKOUT_EVENTS_TOPIC` must exist.
- `ORDER_SERVICE_KAFKA_CHECKOUT_EVENTS_DLT_TOPIC` must exist.
- The DLT topic must have at least as many partitions as the source checkout topic.
- Production enables `ORDER_SERVICE_KAFKA_CHECKOUT_EVENTS_VALIDATE_TOPICS=true` by default, so the app fails startup when these requirements are not met.

## Expected Environment Variables

Use [.env.example](../.env.example) as a starting point.

Expected categories:

- datasource URL/user/password
- Redis host/port/password
- Kafka bootstrap servers
- consumed topic names
- produced topic names
- checkout retry and DLT settings

## Deployment Work Remaining

- Add Dockerfile.
- Add local compose file or shared platform compose integration.
- Add Kubernetes manifest or Helm values.
- Add readiness and liveness probe configuration.
- Add observability configuration.

## Operational Rule

Do not deploy as a production order processor until service layer, persistence, event consumers, idempotency, and error handling are implemented and tested.

For checkout event consumption, do not disable DLT topic validation in production
unless topic readiness is enforced by another deployment gate.
