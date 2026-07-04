# Data Storage

Cart-service uses Redis for active cart state and PostgreSQL for projection read models.

## Redis Cart State

Redis keys:

```text
cart:{userId}
cart:{userId}:v
cart-response:{userId}
store-basket:{userId}:{storeId}
```

Primary cart state:

- Key prefix: `cart:`
- TTL: 7 days
- Value: serialized `RedisCart`

Version key:

- Key: `cart:{userId}:v`
- Value: integer string
- TTL: 7 days
- Used by the CAS Lua script.

GET response cache:

- Key prefix: `cart-response:`
- TTL: 2 minutes
- Derived from Redis cart plus validation/calculation.

## Optimistic CAS Writes

Cart mutations use compare-and-swap instead of Redis locks.

Flow:

1. Read `RedisCart`.
2. Record `expectedVersion`.
3. Mutate cart in memory.
4. Call `saveIfVersionMatches(userId, cart, expectedVersion)`.
5. Lua script compares `cart:{userId}:v` with `expectedVersion`.
6. On match, script writes cart JSON and increments version key.
7. On mismatch, service throws `CartVersionConflictException`.

Missing version keys are treated as version `0`, allowing first cart creation.

## Redis Cart Schema Migration

`RedisCart` has `schemaVersion`.

Rules:

- Current version: use as-is.
- Older version: migrate in memory, then save once through CAS.
- Newer version: skip the cart to avoid data loss during rolling deployment.
- Failed migration does not delete the original Redis cart.

Migration steps implement `CartMigrationStep`.

## PostgreSQL Projections

Projection tables:

- `cart_product_projections`
- `cart_inventory_projections`
- `cart_store_projection`

These are local read models used for validation and response enrichment. They are updated by Kafka event handlers.

## Flyway

Migrations live in:

```text
src/main/resources/db/migration
```

Current migrations:

- `V1__create_cart_product_projections_table.sql`
- `V2__create_cart_inventory_projections_table.sql`
- `V3__create_cart_store_projection_table.sql`
- `V4__make_projection_columns_nullable.sql`

## Query Timeout Rules

Projection reads are guarded by timeouts:

- Global JPA query timeout: 3000 ms.
- Product bulk lookup: 2000 ms.
- Inventory bulk lookup: 1500 ms.
- Store bulk lookup: 1000 ms.
- Hikari connection timeout: 3500 ms.

These limits protect cart validation from hanging behind slow projection queries.

## Schema Change Rules

- Use Flyway migrations for PostgreSQL schema changes.
- Do not mutate existing migration files after they have been applied.
- Add Redis schema migration steps when changing stored Redis cart shape incompatibly.
- Keep JPA entities aligned with migrations.
- Add indexes for validation query paths, not just write paths.
