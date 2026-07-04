# Testing Strategy

## Current Tests

The project currently has the generated application context test:

```text
OrderServiceApplicationTests
```

## Test Commands

Run all tests:

```powershell
.\mvnw.cmd test
```

Run one test class:

```powershell
.\mvnw.cmd test -Dtest=OrderServiceApplicationTests
```

Compile without running tests:

```powershell
.\mvnw.cmd compile
```

## Unit Tests To Add

Add unit tests for:

- status transition rules
- cancellation rules
- proposal resolution rules
- timeline mapping
- Redis key generation
- Redis repository serialization/deserialization
- active-order removal Lua behavior
- lock release Lua behavior

## Controller Tests To Add

Add controller tests for:

- required `X-User-Id`
- required `X-Store-Id`
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
