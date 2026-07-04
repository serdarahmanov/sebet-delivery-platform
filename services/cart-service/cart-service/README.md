# Cart Service

Spring Boot cart microservice for the Sebet delivery platform.

The service owns user cart state, store baskets, promo-code selection, delivery quote state, checkout initiation, and checkout confirmation. It stores live cart state in Redis, uses PostgreSQL projection tables for product/inventory/store lookups, consumes Kafka events to keep those projections current, and calls promotion and delivery services over HTTP.

## Tech Stack

- Java 17
- Spring Boot 4
- Spring Web MVC
- Spring Data Redis
- Spring Data JPA
- Spring Kafka
- PostgreSQL and Flyway
- Maven Wrapper
- Micrometer, Prometheus, OpenTelemetry, Grafana, Tempo

## Identity Contract

The service does not parse JWTs. Upstream infrastructure must pass the authenticated user id with:

```http
X-User-Id: <user-id>
```

## API Base Path

```text
/api/cart
```

Main endpoint groups:

- `GET /api/cart`
- `POST /api/cart/items`
- `POST /api/cart/items/batch`
- `PATCH /api/cart/items/{cartItemId}`
- `DELETE /api/cart/items/{cartItemId}`
- `PATCH /api/cart/store-baskets/{storeId}/address`
- `PATCH /api/cart/store-baskets/{storeId}/delivery-method`
- `POST /api/cart/store-baskets/{storeId}/promo-codes/claim`
- `POST /api/cart/store-baskets/{storeId}/promo-codes/apply`
- `POST /api/cart/store-baskets/{basketId}/checkout/initiate`
- `POST /api/cart/store-baskets/{basketId}/checkout/confirm`
- `DELETE /api/cart/store-baskets/{basketId}`
- `DELETE /api/cart`

See [API Design](docs/04-api-design.md) for endpoint behavior and response conventions.

## Run Locally

Build the application jar, then start the local compose stack:

```powershell
.\mvnw.cmd package
docker compose up --build
```

Or run the Spring Boot app directly after starting Redis, PostgreSQL, and Kafka:

```powershell
.\mvnw.cmd spring-boot:run
```

The local compose file starts cart-service infrastructure and the observability stack. Promotion-service and delivery-service are not included in this compose file; if they are not reachable, the cart service uses degraded downstream behavior.

## Tests

```powershell
.\mvnw.cmd test
```

## Environment

Use [.env.example](.env.example) as the starting point for required runtime settings.

Important production variables:

- `REDIS_HOST`
- `REDIS_PORT`
- `REDIS_PASSWORD`
- `KAFKA_BOOTSTRAP_SERVERS`
- `PROMOTION_SERVICE_URL`
- `DELIVERY_SERVICE_URL`
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

## Documentation

- [Overview](docs/01-overview.md)
- [Architecture](docs/02-architecture.md)
- [Domain Model](docs/03-domain-model.md)
- [API Design](docs/04-api-design.md)
- [Data Storage](docs/05-data-storage.md)
- [Integrations](docs/06-integrations.md)
- [Kafka Events](docs/07-kafka-events.md)
- [Checkout Flow](docs/08-checkout-flow.md)
- [Error Handling](docs/09-error-handling.md)
- [Testing Strategy](docs/10-testing-strategy.md)
- [Deployment](docs/11-deployment.md)
- [Observability](docs/12-observability.md)
- [Architecture Decisions](docs/adr)

## Known Documentation Rules

- Keep this README short and practical.
- Put system explanations in `docs/`.
- Put important technical decisions in `docs/adr/`.
- Update docs when behavior, architecture, or operational requirements change.
