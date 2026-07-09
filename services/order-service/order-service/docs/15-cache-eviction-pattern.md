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

## Tier 1: Internal API — Direct Eviction

The internal API endpoint:

1. Validates and applies the state change to the order.
2. Writes the business outbox event and idempotency record in the same database
   transaction as the state change.
3. Commits.
4. Attempts direct Redis `DEL` after commit.

If `DEL` returns normally (key deleted or key did not exist — both are success),
return `200`. The offset of any later retry is committed.

`DEL` is the only Redis call in this path. There is no `EXISTS` check after
`DEL`: another process could rebuild the key between `DEL` and `EXISTS`, making
a successful eviction appear failed. `DEL` returning normally is the sufficient
and correct success signal.

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

The caller should retry with the same `Idempotency-Key`. The idempotency record
ensures the state change is not re-applied. The eviction path is always re-run
on retry regardless of whether the idempotency record already exists.

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
    String cacheKey(String aggregateId); // full Redis key, used in the fallback event
    void evict(String aggregateId);      // performs the deletion
}
```

`OrderCacheEvictionProjectionHandler` collects all `CacheEvictionStrategy` beans
at startup and builds a `Map<cacheName, strategy>`. When an
`OrderCacheEvictionRequested` event arrives, the handler looks up the strategy by
`cacheName` and calls `strategy.evict(aggregateId)`.

`OrderCacheEvictionService.evictOrRequestEviction` uses the same interface on the
write path: it calls `strategy.evict(aggregateId)` and on failure uses
`strategy.cacheName()` and `strategy.cacheKey(aggregateId)` to populate the
fallback outbox event.

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
- Multiple cache keys per write. If a single write must evict more than one key,
  each key should be attempted independently and a separate fallback event should
  be emitted per recoverable Redis eviction failure.
