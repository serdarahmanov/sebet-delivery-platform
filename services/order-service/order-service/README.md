# Order Service

Spring Boot order lifecycle microservice for the Sebet delivery platform.

The service is designed to receive checkout confirmations from cart-service, create orders, expose customer and store order APIs, maintain Redis-backed hot order views, and support live tracking through WebSocket/STOMP (planned).

The current codebase contains the API/DTO/cache skeleton, durable order persistence, Flyway schema migration, repository tests, an internal order creation service, checkout-event-to-command mapping, Kafka retry/DLT handling, a Kafka checkout-event consumer, Redis locking around checkout order creation, Redis hot-view writes during order creation, customer read services, store read services, the first store lifecycle write endpoints, and a global exception handler with consistent error responses. WebSocket broker, remaining REST write service implementations, driver/internal service methods, and order event producers are still pending.

## Tech Stack

- Java 17
- Spring Boot 4
- Spring Web MVC
- Spring WebSocket
- Spring Data Redis
- Spring Data JPA
- Spring Kafka
- PostgreSQL and Flyway
- Maven Wrapper
- Lombok

## Identity Contracts

Customer endpoints require:

```http
X-User-Id: <user-id>
```

Store endpoints require:

```http
X-Store-Id: <store-id>
```

These headers are enforced by MVC interceptors.

## API Base Paths

```text
/api/v1/orders
/api/v1/store/orders
```

See [API Design](docs/04-api-design.md) for endpoint groups and current implementation status.

## Run Locally

Run the Spring Boot app:

```powershell
.\mvnw.cmd spring-boot:run
```

Build the project:

```powershell
.\mvnw.cmd package
```

## Tests

```powershell
.\mvnw.cmd test
```

Tests use Testcontainers for PostgreSQL, Redis, and Kafka. Docker Desktop must
be running before executing the test command.

## Environment

Use [.env.example](.env.example) as the starting point for expected runtime settings.

The current `application.yml` sets the service name, disables JPA Open Session
in View, and keeps the checkout Kafka listener disabled by default for local
runs. Production environment-backed settings live in `application-prod.yml`,
where the checkout listener is enabled by default. Redis, PostgreSQL, Kafka, and
downstream/event settings still need full runtime configuration before this
service can run as a complete order processor.

## Documentation

- [Overview](docs/01-overview.md)
- [Architecture](docs/02-architecture.md)
- [Domain Model](docs/03-domain-model.md)
- [API Design](docs/04-api-design.md)
- [Data Storage](docs/05-data-storage.md)
- [Integrations](docs/06-integrations.md)
- [Kafka Events](docs/07-kafka-events.md)
- [Order Lifecycle](docs/08-order-lifecycle.md)
- [Error Handling](docs/09-error-handling.md)
- [Testing Strategy](docs/10-testing-strategy.md)
- [Deployment](docs/11-deployment.md)
- [Observability](docs/12-observability.md)
- [Implementation Gaps](docs/13-implementation-gaps.md)
- [Architecture Decisions](docs/adr)

## Documentation Rules

- Keep this README short and practical.
- Put system behavior and planned design in `docs/`.
- Put important technical decisions in `docs/adr/`.
- Keep docs explicit about what is implemented versus planned.
