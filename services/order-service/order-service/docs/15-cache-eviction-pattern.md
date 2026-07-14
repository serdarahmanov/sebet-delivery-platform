# Cache Eviction Pattern

## Problem

When an internal API write commits a state change to PostgreSQL, any Redis cache
key that reflects that state becomes stale. The write must invalidate the key
before returning. Redis may be unavailable or unreachable at that moment, so the
eviction cannot be treated as a simple best-effort call.

The system needs a guarantee: the key will be evicted, even if Redis is down at
the time of the write.

## Pattern Overview

Three-tier contract:

```
Tier 1 — direct eviction (synchronous, best case)
Tier 2 — fallback event (asynchronous, Redis availability failed)
Tier 3 — hard failure (fallback itself failed, return 503)
```

Each tier is a safety net for the one above it. Under normal conditions only
Tier 1 runs. Tier 2 absorbs transient Redis outages. Tier 3 surfaces the rare
case where the system cannot guarantee eventual eviction through any path.

## Tier 1: Write API — Direct Eviction

The write endpoint:

1. Validates and applies the state change to the order.
2. Writes the business outbox event and idempotency record in the same database
   transaction as the state change.
3. Commits.
4. Attempts direct Redis eviction after commit.

If the eviction strategy returns normally, return `200`. A missing key is still
success because the desired postcondition is that the stale hot view is gone.
The offset of any later retry is committed.

There is no `EXISTS` check after eviction: another process could rebuild the key
between eviction and `EXISTS`, making a successful eviction appear failed. The
strategy returning normally is the sufficient and correct success signal.

## Tier 2: Fallback Event — Async Eviction via Kafka

If the cache eviction strategy throws a recoverable Redis availability failure
such as `RedisConnectionFailureException` or a Redis command timeout, write an
`OrderCacheEvictionRequested` event to the outbox in a new transaction. The
event records the original exception type and message so operators can tell
whether Redis was unavailable or the direct eviction result was unknown:

```json
{
  "eventType": "OrderCacheEvictionRequested",
  "data": {
    "orderId": "order-id",
    "cacheName": "C2",
    "cacheKey": "order:order-id",
    "cacheKeys": ["order:order-id"],
    "reason": "DRIVER_ASSIGNMENT_CHANGED",
    "sourceAction": "INTERNAL_ASSIGN_DRIVER",
    "idempotencyKey": "idempotency-key",
    "failureType": "RedisConnectionFailureException",
    "failureMessage": "...",
    "requestedAt": "2026-07-09T10:00:00Z"
  }
}
```

Debezium picks this up from the outbox table and publishes it to `order-events`.
Return `200` to the caller. The state change is already durable; eviction will
happen asynchronously unless recording the fallback event itself fails.

Other runtime failures, such as serialization, invalid data, or programming
errors inside a strategy, are not converted into fallback events. They propagate
as normal application errors because replaying the same eviction later would not
fix them.

## Tier 3: Hard Failure — 503

If writing the fallback event also fails (outbox write throws), the system cannot
guarantee eviction through any path. Throw `CacheInvalidationFailedException`,
which the global exception handler maps to `503 CACHE_INVALIDATION_FAILED`.

The caller should retry the same request. For endpoints that require
`Idempotency-Key`, reuse the same key so the state change is not re-applied. The
eviction path is always re-run on retry regardless of whether an idempotency
record already exists.

## Tier 2 Consumer: OrderCacheEvictionProjectionConsumer

The consumer listens to `order-events` with a dedicated container factory
(`cacheEvictionContainerFactory`) configured with `MANUAL` ack mode and its own
consumer group. Non-`OrderCacheEvictionRequested` event types are ignored.
For `OrderCacheEvictionRequested` events, the handler reads `data.cacheName`
and dispatches to the matching `CacheEvictionStrategy` from the strategy
registry. If no strategy is registered for that `cacheName`, the event is
skipped with a warning log.

On a valid event:
- Look up the `CacheEvictionStrategy` by `cacheName`.
- Call `strategy.evict(aggregateId)`.
- If eviction succeeds, call `ack.acknowledge()` — offset committed, message done.
- If eviction throws `RedisConnectionFailureException` or `QueryTimeoutException`,
  pause the container and return without acknowledging. The offset is not
  committed and the message will be replayed.

Because the offset is not committed on failure, the container holds its position
in the partition. No message behind the failed one is processed until the
container resumes.

## Recovery: RedisRecoveryScheduler

`RedisRecoveryScheduler` runs every 10 seconds. It checks whether the container
has a pause request. If not, it exits immediately (one in-memory boolean read, no
network call). If the container is paused, it probes Redis with a dedicated
connection that has a 500 ms command timeout. On Kubernetes, pod-to-pod latency
is under 1 ms so 500 ms is more than enough to detect genuine unavailability
without false positives.

When Redis responds without throwing, the scheduler resumes the container. The
consumer replays the uncommitted message and, if Redis is now healthy, evicts the
key and commits the offset.

The health-check connection is separate from the main Redis connection factory.
It uses its own `LettuceConnectionFactory` with the same host, port, and password
as the main factory but with the 500 ms timeout. This prevents the short health
probe timeout from affecting the main Redis operations.

## Strategy Registry: CacheEvictionStrategy

All cache-specific eviction logic is encapsulated behind `CacheEvictionStrategy`:

```java
public interface CacheEvictionStrategy {
    String cacheName();                  // registry key, e.g. "C2"
    String cacheKey(String aggregateId); // primary Redis key, used in the fallback event
    List<String> cacheKeys(String id);   // all affected keys, defaults to List.of(cacheKey(id))
    void evict(String aggregateId);      // performs the deletion
}
```

`OrderCacheEvictionProjectionHandler` collects all `CacheEvictionStrategy` beans
at startup and builds a `Map<cacheName, strategy>`. When an
`OrderCacheEvictionRequested` event arrives, the handler looks up the strategy by
`cacheName` and calls `strategy.evict(aggregateId)`.

`OrderCacheEvictionService.evictOrRequestEviction` uses the same interface on the
write path: it calls `strategy.evict(aggregateId)` and on failure uses
`strategy.cacheName()`, `strategy.cacheKey(aggregateId)`, and
`strategy.cacheKeys(aggregateId)` to populate the fallback outbox event.

`OrderCacheEvictionService.requestEvictionAfterUpdateFailure` is a variant for
write paths that perform an atomic Redis update rather than a pure eviction.
When the Lua update script throws a recoverable Redis failure, the caller passes
the exception directly to `requestEvictionAfterUpdateFailure` along with the
eviction strategy that can clean up the now-inconsistent keys. The method skips
the direct eviction attempt (the exception proves Redis is already unreachable)
and goes straight to recording an `OrderCacheEvictionRequested` fallback event.
Non-Redis runtime failures are re-thrown without recording a fallback event.

Grouped strategies are allowed when one logical write must mutate multiple Redis
structures atomically. `CANCELLED_ORDER_HOT_VIEWS` removes the order id from C1
and C1b and deletes C2, C3, C4, and C6 with one Redis Lua script. If Redis is
unavailable, one fallback event retries the full grouped cleanup.

`PROPOSE_CHANGES_HOT_VIEWS` groups C8, C4, and C6 for the propose-changes path.
Its `evict(orderId)` calls `redisTemplate.delete(List.of(C8, C4, C6))` — a
single multi-DEL without a Lua script, which is sufficient for eviction because
all three keys are already stale when the consumer processes the event. The
`cacheKey` primary key is C8 (`order:proposals:{orderId}`).

`CANCEL_ACTIVE_PROPOSAL_HOT_VIEWS` also groups C8, C4, and C6 for the proposal
withdrawal path. The direct write path uses `cancelActiveProposalRedisUpdateScript`
to delete C8, set C4 back to `CONFIRMED`, and remove `AWAITING_CUSTOMER_RESPONSE`
entries from C6 atomically. If that update fails with a recoverable Redis failure,
the fallback event deletes C8/C4/C6 so later reads rebuild from PostgreSQL.

`APPLY_PROPOSAL_HOT_VIEWS` groups C2, C8, C4, and C6 for the promo-service
proposal application path. The direct write path uses
`applyProposalRedisUpdateScript` to delete stale C2, delete C8, set C4 back to
`CONFIRMED`, and remove `AWAITING_CUSTOMER_RESPONSE` entries from C6 atomically.
If that update fails with a recoverable Redis failure, the fallback event deletes
all four keys so later reads rebuild from PostgreSQL.

Note that `PROPOSE_CHANGES_HOT_VIEWS` is not used on the write path for direct
eviction. The write path instead performs an atomic update (not an eviction) via
`proposeChangesRedisUpdateScript`, which writes C8, C4, and C6 in one round-trip.
Only when that Lua script throws a recoverable Redis failure does the endpoint
reach the fallback path via `requestEvictionAfterUpdateFailure`.

## Applying This Pattern Elsewhere

To add eviction for a new cache key, one new class is all that is needed:

### 1. Implement CacheEvictionStrategy

```java
@Component
public class C4CacheEvictionStrategy implements CacheEvictionStrategy {

    private final OrderStatusRedisRepository repo;

    @Override public String cacheName()             { return "C4"; }
    @Override public String cacheKey(String id)     { return RedisKeys.orderStatus(id); }
    @Override public void evict(String aggregateId) { repo.delete(aggregateId); }
}
```

The handler and the service both pick it up automatically via Spring's bean
collection. No changes to `OrderCacheEvictionProjectionHandler` or
`OrderCacheEvictionService` are needed.

### 2. Call the service from the write path

```java
orderCacheEvictionService.evictOrRequestEviction(
    orderId,
    c4Strategy,          // inject the specific strategy
    "STATUS_CHANGED",    // reason for the outbox event
    sourceAction,
    idempotencyKey
);
```

The three-tier contract (direct eviction -> fallback event -> 503) applies
automatically for any strategy passed to this method when direct eviction fails
with a recoverable Redis availability or timeout error.

For multiple related keys, implement one grouped strategy and make its
`evict(...)` method atomic, for example through a Lua script. Its `cacheKeys(...)`
should return every key the event needs to expose for observability.

### 3. Consumer and scheduler

The existing `OrderCacheEvictionProjectionConsumer` and `RedisRecoveryScheduler`
require no changes. The consumer already dispatches by `cacheName` and the
scheduler manages the container pause/resume lifecycle.

## What This Pattern Does Not Cover

- Non-Redis strategy failures. Serialization, invalid data, and programming
  errors are intentionally not retried through `OrderCacheEvictionRequested`.
- Extended Redis outages that outlast all Kafka retry attempts. The consumer
  pause mechanism keeps the message alive indefinitely, so this is not a concern
  as long as Redis eventually recovers.
- Cross-system atomicity. The database write and Redis eviction are still
  separate systems. The fallback event guarantees retry after a committed write;
  it does not make PostgreSQL and Redis one transaction.
