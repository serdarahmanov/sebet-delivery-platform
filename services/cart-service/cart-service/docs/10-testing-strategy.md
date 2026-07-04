# Testing Strategy

## Current Test Areas

The project currently includes tests for:

- Spring application context.
- `GET /api/cart` response building.
- Cart response cache behavior.
- Batch upsert behavior.
- Promo claim/apply flows.
- Clear basket behavior.
- Store basket validation controller behavior.
- Product event handler safety behavior.

`CartServiceApplicationTests` is disabled because it requires full infrastructure such as Redis, PostgreSQL, and Kafka. It is not intended to run as an isolated unit test.

## Unit Tests

Use unit tests for:

- cart calculations
- response builders
- validation result merging
- promo result application
- Redis cart mapping
- schema migration steps

Unit tests should avoid Redis, Kafka, and HTTP when the behavior can be tested in memory.

## Controller Tests

Use controller tests for:

- route mapping
- request validation
- required `X-User-Id` behavior
- status codes
- response shape

## Integration Tests

Add integration tests when behavior depends on:

- Redis serialization or TTL
- CAS Lua script behavior
- Flyway schema and JPA entity alignment
- Kafka listener error handling
- WebClient integration behavior

## Checkout Tests

Checkout needs focused tests for:

- blocked validation responses
- executor rejection
- timeout handling
- address update dirty-save behavior
- quote refresh behavior
- CAS before publish
- publish failure after basket removal

## Test Command

```powershell
.\mvnw.cmd test
```

Run one test class:

```powershell
.\mvnw.cmd test -Dtest=CartServicePromoFlowTest
```

Run one test method:

```powershell
.\mvnw.cmd test -Dtest=CartServicePromoFlowTest#someMethod
```

Compile without running tests:

```powershell
.\mvnw.cmd compile
```

## Release Validation

Before release, run:

```powershell
.\mvnw.cmd package
```

For deployment changes, also validate Docker build and Kubernetes manifests.
