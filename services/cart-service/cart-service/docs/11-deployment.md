# Deployment

## Docker

The Docker image uses:

```text
eclipse-temurin:17-jre-jammy
```

The image copies:

- bundled OpenTelemetry Java agent
- built application jar from `target/*.jar`

Runtime starts with:

```text
java -jar app.jar
```

## Local Compose

`docker-compose.yaml` starts:

- cart-service
- Redis
- PostgreSQL
- Kafka
- OpenTelemetry Collector
- Tempo
- Prometheus
- Grafana

Promotion-service and delivery-service are not included in this compose file.

## Production Profile

Production uses:

```text
SPRING_PROFILES_ACTIVE=prod
```

`application-prod.yaml` requires:

- `REDIS_HOST`
- `REDIS_PORT`
- `REDIS_PASSWORD`
- `KAFKA_BOOTSTRAP_SERVERS`
- `PROMOTION_SERVICE_URL`
- `DELIVERY_SERVICE_URL`

Datasource settings are expected through Spring environment variables:

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

## Kubernetes

Manifest:

```text
k8s/deployment.yaml
```

Current deployment defines:

- 2 replicas
- CPU and memory requests/limits
- Prometheus scrape annotations
- readiness probe
- liveness probe
- OTel environment variables
- Redis, datasource, and Kafka secret references

## Known Deployment Gap

`application-prod.yaml` requires `PROMOTION_SERVICE_URL` and `DELIVERY_SERVICE_URL`, but the current Kubernetes deployment manifest does not define them.

Before production deployment, add these variables through secrets, config maps, Helm values, or another deployment-time injection mechanism.

## Health Probes

Kubernetes probes use:

```text
/actuator/health/readiness
/actuator/health/liveness
```

If probe groups are required by Spring Boot configuration, verify they are enabled before deploying.

## Operational Rule

Do not deploy config changes without validating that Redis, PostgreSQL, Kafka, promotion-service, delivery-service, and OTel Collector endpoints resolve from the runtime environment.
