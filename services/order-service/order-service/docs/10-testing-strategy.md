# Testing Strategy

## Current Tests

The project currently has:

- Redis key generation tests.
- MVC interceptor tests for `X-User-Id` and `X-Store-Id`.
- Spring context test with PostgreSQL, Redis, and Kafka Testcontainers.
- JPA repository tests against PostgreSQL Testcontainers.
- Order creation service integration tests.
- Checkout event mapper unit tests.

The Spring context test uses Testcontainers to start PostgreSQL, Redis, and
Kafka, then boots the application with the `test` Spring profile.

## Test Commands

Run all tests:

```powershell
.\mvnw.cmd test
```

On Windows, the `windows-testcontainers` Maven profile is activated
automatically. It configures Surefire with the Docker Desktop named pipe and
Docker API version required by Testcontainers, so no manual environment setup is
needed for the normal test command.

Run one test class:

```powershell
.\mvnw.cmd test -Dtest=OrderServiceApplicationTests
```

Compile without running tests:

```powershell
.\mvnw.cmd compile
```

## Unit Tests To Add

Implemented baseline tests:

- Redis key generation
- required `X-User-Id`
- required `X-Store-Id`
- checkout event mapping
- durable order repository behavior
- order creation behavior

Add unit tests for:

- status transition rules
- cancellation rules
- proposal resolution rules
- timeline mapping
- Redis repository serialization/deserialization
- active-order removal Lua behavior
- lock release Lua behavior

## Controller Tests To Add

Add controller tests for:

- request validation
- route mappings
- planned status codes

## Integration Tests To Add

Implemented integration coverage:

- Flyway migration application
- JPA order/item/status-history persistence
- cart id uniqueness
- item line-number ordering
- initial order creation for immediate and scheduled orders
- duplicate cart id handling in order creation

Add integration tests when implemented:

- Redis repositories
- Kafka consumer idempotency
- Kafka retry/DLT behavior
- WebSocket/STOMP subscriptions

## Release Validation

Before release:

```powershell
.\mvnw.cmd package
```
