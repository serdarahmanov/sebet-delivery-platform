# Contributing

This service is part of the Sebet delivery platform. Keep changes scoped, tested, and documented.

## Before Changing Code

- Read [Architecture](docs/02-architecture.md) for module boundaries.
- Read [Data Storage](docs/05-data-storage.md) before changing Redis keys, repositories, or future Flyway migrations.
- Read [Order Lifecycle](docs/08-order-lifecycle.md) before changing status transitions.
- Read [Implementation Gaps](docs/13-implementation-gaps.md) before assuming behavior is implemented.
- Add or update ADRs for decisions that affect long-term architecture.

## Local Validation

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

For dependency, packaging, or deployment changes, also run:

```powershell
.\mvnw.cmd package
```

## Documentation Rules

- Keep `README.md` short.
- Put deeper explanations in `docs/`.
- Keep planned behavior clearly marked as planned.
- Update docs when API, cache, event, or lifecycle behavior changes.

## Commit Hygiene

- Do not mix unrelated behavior changes.
- Mention user-visible API, data, or deployment changes in the commit message.
- Update `CHANGELOG.md` when a change affects release behavior.
