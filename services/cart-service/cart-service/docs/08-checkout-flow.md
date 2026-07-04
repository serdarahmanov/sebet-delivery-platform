# Checkout Flow

Checkout has two explicit steps:

```http
POST /api/cart/store-baskets/{basketId}/checkout/initiate
POST /api/cart/store-baskets/{basketId}/checkout/confirm
```

## Initiate Checkout

`initiateCheckout` prepares a basket for checkout and returns either `READY` or blocked issues.

Flow:

1. Load cart from Redis.
2. Resolve `storeId` from basket id.
3. Validate basket exists and has items.
4. Apply requested address if changed.
5. Run checkout validation asynchronously on `checkoutExecutor`.
6. Delivery checkout quote and promotion validation happen inside `validateForCheckout`.
7. Save cart only if address, quote, or schedule state changed.
8. Return blocked response if blocking issues exist.
9. Calculate checkout summary.
10. Return ready response with quote, items, promos, warnings, and totals.

## Confirm Checkout

`confirmCheckout` validates again and publishes a checkout event after persisting basket removal.

Flow:

1. Load cart from Redis.
2. Resolve `storeId` from basket id.
3. Validate basket exists and has items.
4. Ensure address exists.
5. Run checkout validation asynchronously on `checkoutExecutor`.
6. Collect blocking issues.
7. If blocked, evict derived caches and return rejected response.
8. Build `CheckoutConfirmedEvent`.
9. Remove basket from cart in memory.
10. Save cart through CAS.
11. Evict derived caches.
12. Publish checkout event.

## CAS Before Event Publish

The basket is removed and persisted before publishing `CheckoutConfirmed`.

Reason:

- If publish happened first and CAS failed, downstream services could process an order while the basket still exists in Redis.
- With current behavior, if CAS succeeds but Kafka publish fails, the basket is gone and the client receives 503.

This is an accepted tradeoff until an outbox pattern is introduced.

## Executor

Checkout validation runs on `checkoutExecutor`.

Settings:

- core pool size: 4
- max pool size: 16
- queue capacity: 100
- rejection policy: `AbortPolicy`
- thread prefix: `checkout-`

Executor rejection returns HTTP 503.

## Timeout

Both checkout steps wait up to 8 seconds for validation.

Timeout behavior:

- cancel future
- record metric
- return HTTP 503

## Delivery Quote Freshness

Normal cart browsing accepts quotes that expire after `now`.

Checkout requires a stricter buffer: quotes must be valid beyond `now + 30 seconds`. Nearly expired quotes are treated as missing and re-fetched during checkout validation.
