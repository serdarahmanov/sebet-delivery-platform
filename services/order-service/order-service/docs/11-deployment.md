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
- Kafka Connect with Debezium PostgreSQL connector
- Debezium outbox connector configuration
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
- optional service instance id for Redis checkout-lock ownership
- internal API shared secret (`ORDER_SERVICE_INTERNAL_SECRET`) for `X-Internal-Key` validation
- Kafka bootstrap servers
- consumed topic names
- produced topic names
- checkout retry and DLT settings
- Debezium connector database host/user/password/slot/publication settings

## Debezium Outbox Connector

Order business events are written to `outbox_event` and published by Debezium,
not by this Spring Boot process. The sample connector configuration is:

```text
debezium/order-service-outbox-connector.json
```

The connector contract is documented in [Debezium Outbox](14-debezium-outbox.md).
Before enabling it, provision:

- Kafka Connect worker with Debezium PostgreSQL connector installed
- `order-events` topic
- PostgreSQL logical decoding enabled
- replication slot/publication strategy for `public.outbox_event`
- Debezium database user with required replication and table privileges

## Deployment Work Remaining

- Add Dockerfile.
- Add local compose file or shared platform compose integration.
- Add Kubernetes manifest or Helm values.
- Add readiness and liveness probe configuration.
- Add observability configuration.
- Add managed Debezium connector deployment/secrets for each environment.
- Add outbox cleanup CronJob or equivalent platform job with Kafka Connect
  health and PostgreSQL replication lag checks.

## Operational Rule

Do not deploy as a complete production order processor until REST-facing service workflows, remaining event consumers/producers, lifecycle transition handlers, and error handling are implemented and tested.

For checkout event consumption, do not disable DLT topic validation in production
unless topic readiness is enforced by another deployment gate.

`ORDER_SERVICE_INTERNAL_SECRET` must be set to a non-blank value in every
environment. The service fails startup without it so internal endpoints cannot
accidentally accept arbitrary non-blank keys.
