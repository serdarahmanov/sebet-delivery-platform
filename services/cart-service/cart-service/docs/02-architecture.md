# Architecture

## High-Level Shape

Cart-service is a Spring Boot microservice with clear boundary layers:

```text
HTTP controller
  -> application services
  -> Redis repositories, JPA repositories, HTTP clients, Kafka publishers
```

The service owns cart behavior but depends on projections and downstream services for current product, inventory, store, promotion, and delivery data.

## Main Packages

```text
com.sebet.cartservice
  cart/
    controller/
    service/
    checkout/
    repository/
    mapper/
    validation/
    model/
    dto/
    delivery/
    promotion/
    product/
    inventory/
    store/
    config/
    metrics/
    exception/
    migration/
```

## Request Flow

Typical mutation flow:

1. `CartController` receives a request and reads `X-User-Id`.
2. `CartService` loads or creates the Redis cart.
3. Service records the current cart version.
4. Service mutates the in-memory cart.
5. Validation and calculation run when response behavior needs them.
6. `CartRedisRepository.saveIfVersionMatches()` performs one CAS write.
7. Cart response and basket caches are evicted.
8. Response DTO is built and returned.

## Read Flow

`GET /api/cart` uses `CartResponseCacheService`:

1. Return cached lightweight response from `cart-response:{userId}` when present.
2. On cache miss, load Redis cart.
3. Validate and calculate in memory.
4. Build `CartSummaryResponse`.
5. Cache the result for 2 minutes.

## Validation and Calculation

Validation is handled by `CartValidationService` and validators under `cart.validation`.

Validation uses:

- Product projections.
- Inventory projections.
- Store projections.
- Delivery fee resolution.
- Promotion evaluation.

Calculation is handled by `CartCalculationService` after validation. It computes item totals, basket totals, discounts, delivery fee, service fee, and final totals.

## Key Design Patterns

- Validators are stateless Spring components.
- Validation lookup data is fetched in bulk before validators run.
- Validators mutate a shared validation accumulator instead of writing directly to cart state.
- Delivery fee resolution mutates the in-memory `RedisCart` object but does not save to Redis directly.
- Mutating operations should persist cart state through a single outer CAS save.
- Projection event handlers use version checks to drop stale out-of-order events.
- Checkout validation is offloaded to the dedicated `checkoutExecutor`.

## Caching Rules

- Redis cart state is the source of truth for active carts.
- `GET /api/cart` response cache is derived data.
- Store basket cache is derived data.
- Mutations evict affected derived caches.
- Read-only response building must not persist delivery quote changes.

## Dependency Rules

- Controllers should stay thin.
- Business behavior belongs in services.
- Redis serialization and CAS details belong in repositories.
- HTTP integration details belong in client classes.
- Kafka event parsing and projection updates belong in projection event packages.
- DTOs should not own business rules.
