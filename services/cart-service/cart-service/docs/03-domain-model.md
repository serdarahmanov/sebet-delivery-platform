# Domain Model

## Cart

A cart belongs to one user and is stored in Redis as `RedisCart`.

Important fields:

- `cartId`: stable cart identifier.
- `userId`: owner id from `X-User-Id`.
- `version`: optimistic locking token.
- `schemaVersion`: Redis cart schema version.
- `storeBaskets`: baskets grouped by store.
- `createdAt` and `updatedAt`.

## Store Basket

A store basket groups cart items for one store.

Important fields:

- `storeId`
- `items`
- `promoCodes`
- `addressId`
- `deliveryQuote`
- `selectedDeliveryMethodId`
- `scheduleType`
- `scheduledFor`

Basket ids are derived as:

```text
{cartId}:{storeId}
```

## Cart Item

A cart item represents a product selected from a specific store.

Important fields:

- `cartItemId`
- `productId`
- `storeId`
- `quantity`
- timestamps

Validation enriches cart items with product and inventory projection data.

## Promo Code

Promo codes are stored per basket in Redis and evaluated by promotion-service.

States:

- `SAVED`: claimed but not selected.
- `SELECTED`: selected for application.

Promotion-service decides whether selected codes are valid and what discounts apply.

Lifecycle:

1. `claimPromoCode` temporarily evaluates the code through the basket response build path.
2. If the code is claimable, it is persisted as `SAVED`.
3. `applyPromoCodes` switches claimed codes between `SELECTED` and `SAVED`.
4. Only selected codes are sent to promotion-service during validation.
5. Promotion-service returns applied discounts, promo issues, and selection metadata.

## Delivery Quote

Delivery quote state is stored on a store basket.

It can include:

- quote id
- amount
- currency
- ETA range
- expiry timestamp
- available delivery options

Checkout uses stricter quote freshness than normal cart browsing.

## Issues

The service models validation issues at multiple scopes:

- cart
- store
- store basket
- item

Issues can be warnings or blocking. Checkout rejects baskets with blocking issues.

## Projections

PostgreSQL projections are local read models owned by cart-service:

- product projections
- inventory projections
- store projections

They are updated from Kafka events and used for validation and response enrichment.
