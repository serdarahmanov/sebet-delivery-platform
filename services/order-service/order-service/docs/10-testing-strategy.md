# Testing Strategy

## Current Tests

The project currently has:

- Redis key generation tests.
- MVC interceptor tests for `X-User-Id` and `X-Store-Id`.
- Spring context test with PostgreSQL, Redis, and Kafka Testcontainers.
- JPA repository tests against PostgreSQL Testcontainers.
- Order creation service integration tests.
- Checkout event mapper unit tests.
- Checkout event processor lock unit tests.
- Checkout Kafka listener and retry/DLT integration tests against real brokers.
- `CustomerOrderQueryService` unit tests — 34 tests covering all 10 read methods (cache hit, DB fallback, ownership denial, timeline building, orderNumber format, batch item query).
- `CustomerOrderQueryService` integration tests — 12 tests against real Postgres + Redis containers covering the full read path (history feed, active orders, smart router, DELIVERED/CANCELLED flows, ownership checks, C4 expiry fallback).

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
- checkout event consumer delegation
- checkout event processor Redis lock behavior
- checkout Kafka listener integration
- durable order repository behavior
- order creation behavior
- Redis hot-view writes for immediate and scheduled order creation

Add unit tests for:

- status transition rules
- cancellation rules
- proposal resolution rules
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
- checkout event processor mapping, lock handling, and duplicate-path behavior
- checkout event processor Redis hot-view initialization and recovery behavior from current database state
- Kafka listener integration against a real broker
- Kafka retry/DLT behavior
- checkout event consumption with Redis lock support

Add integration tests when implemented:

- store-facing read and write service methods
- order status transition service methods
- Redis repositories
- WebSocket/STOMP subscriptions

## Release Validation

Before release:

```powershell
.\mvnw.cmd package
```
