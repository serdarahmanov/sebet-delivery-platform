# Testing Strategy

## Current Tests

The project currently has a Spring context test:

```text
OrderServiceApplicationTests
```

This test uses Testcontainers to start PostgreSQL, Redis, and Kafka, then boots
the application with the `test` Spring profile.

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

Implemented baseline unit tests:

- Redis key generation
- required `X-User-Id`
- required `X-Store-Id`

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

Add integration tests when implemented:

- Redis repositories
- Flyway migrations
- JPA queries
- Kafka consumer idempotency
- Kafka retry/DLT behavior
- WebSocket/STOMP subscriptions

## Release Validation

Before release:

```powershell
.\mvnw.cmd package
```
