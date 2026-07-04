# Contributing

This service is part of the Sebet delivery platform. Keep changes scoped, tested, and documented.

## Before Changing Code

- Read [Architecture](docs/02-architecture.md) for module boundaries.
- Read [Data Storage](docs/05-data-storage.md) before changing Redis, Flyway, or projection behavior.
- Read [Checkout Flow](docs/08-checkout-flow.md) before changing checkout initiation or confirmation.
- Add or update ADRs for decisions that affect long-term architecture.

## Local Validation

Run tests before handing off code changes:

```powershell
.\mvnw.cmd test
```

Run a single test class:

```powershell
.\mvnw.cmd test -Dtest=CartServicePromoFlowTest
```

Run a single test method:

```powershell
.\mvnw.cmd test -Dtest=CartServicePromoFlowTest#someMethod
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
- Keep docs aligned with source code and runtime config.
- Prefer small focused docs over one large mixed document.

## Commit Hygiene

- Do not mix unrelated behavior changes.
- Mention user-visible API, data, or deployment changes in the commit message.
- Update `CHANGELOG.md` when a change affects release behavior.
