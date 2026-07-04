# Deployment

## Current State

This service does not currently include Docker, docker-compose, Kubernetes, or environment-specific Spring profiles.

Only `application.properties` exists, and it currently sets:

```properties
spring.application.name=order-service
```

## Required Runtime Dependencies

Before complete deployment, the service will need:

- PostgreSQL
- Redis
- Kafka
- topic configuration
- service profile configuration
- health/metrics exposure settings

## Expected Environment Variables

Use [.env.example](../.env.example) as a starting point.

Expected categories:

- datasource URL/user/password
- Redis host/port/password
- Kafka bootstrap servers
- consumed topic names
- produced topic names

## Deployment Work Remaining

- Add Dockerfile.
- Add local compose file or shared platform compose integration.
- Add production profile.
- Add Kubernetes manifest or Helm values.
- Add readiness and liveness probe configuration.
- Add observability configuration.

## Operational Rule

Do not deploy as a production order processor until service layer, persistence, event consumers, idempotency, and error handling are implemented and tested.
