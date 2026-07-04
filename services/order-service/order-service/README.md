# Order Service

Spring Boot order lifecycle microservice for the Sebet delivery platform.

The service is designed to receive checkout confirmations from cart-service, create orders, expose customer and store order APIs, maintain Redis-backed hot order views, and support live tracking through WebSocket/STOMP. The current codebase contains the API/DTO/cache skeleton; service layer, database schema, Kafka consumers, WebSocket broker, and global error handling are still pending.

## Tech Stack

- Java 17
- Spring Boot 4
- Spring Web MVC
- Spring WebFlux
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

## Environment

Use [.env.example](.env.example) as the starting point for expected runtime settings.

The current `application.properties` only sets the service name. Redis, PostgreSQL, Kafka, and downstream/event settings still need full runtime configuration before this service can run as a complete order processor.

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
