# Order Service

Manages the full lifecycle of customer orders in the Sebet delivery platform —
from checkout confirmation through live tracking to delivery verification.

---

## Table of Contents

1. [Tech Stack](#tech-stack)
2. [Architecture Overview](#architecture-overview)
3. [Order Status Lifecycle](#order-status-lifecycle)
4. [Proposal Flow](#proposal-flow)
5. [Redis Cache Layer](#redis-cache-layer)
6. [Kafka](#kafka)
7. [WebSocket — STOMP](#websocket--stomp)
8. [REST API — Customer](#rest-api--customer)
9. [REST API — Store](#rest-api--store)
10. [Response DTOs](#response-dtos)
11. [Shared Enums](#shared-enums)
12. [Package Structure](#package-structure)
13. [Pending / TODOs](#pending--todos)

---

## Tech Stack

| Concern | Technology |
|---|---|
| Runtime | Java 17, Spring Boot 4.0.6 |
| Web | Spring Web MVC + Spring WebFlux |
| Persistence | PostgreSQL 15, Spring Data JPA, Flyway |
| Cache | Redis, Spring Data Redis |
| Messaging | Apache Kafka, Spring Kafka |
| Validation | Jakarta Bean Validation |
| Utilities | Lombok |

---

## Architecture Overview

The order-service is **event-driven at its entry point** — orders are not
created via a REST call; they are created by consuming the `CheckoutConfirmedEvent`
emitted by the cart-service over Kafka.

Once an order exists, the service exposes:
- **REST endpoints** for reading order data (history, detail, tracking fallback)
- **REST endpoints** for store operations (accept, reject, propose changes, etc.)
- **WebSocket (STOMP)** for pushing live updates to the customer's tracking screen
- **Cache-first reads** — hot data is always served from Redis; the DB is the fallback

```
cart-service
  └── Kafka: checkout-events
        └── OrderCreatedConsumer (TODO)
              ├── writes DB
              ├── writes Cache 2 (order snapshot) — includes storeId, storeName
              ├── writes Cache 1  (user active order set)
              ├── writes Cache 1b (store active order set)
              ├── writes Cache 1c (store scheduled ZSET, if SCHEDULED)
              ├── writes Cache 4  (status)
              └── writes Cache 6  (timeline: PLACED)

delivery-service
  └── Kafka: order.arrived
        └── OrderArrivedConsumer (TODO)
              ├── generates 2-digit verification code
              ├── writes DB (verification_code, code_generated_at)
              ├── writes Cache 7 (verification code)
              └── pushes WebSocket: { status: ARRIVED, code: "47" }

customer app
  ├── GET /api/v1/orders/active/{orderId}   → tracking screen mount (C2 + C4 + C6 + C7)
  └── WS  /ws → /topic/orders/{orderId}/tracking  → live GPS, ETA, status, timeline

store app
  ├── GET  /api/v1/store/orders/active      → kitchen dashboard (C1b → C2 + C4)
  ├── GET  /api/v1/store/orders/scheduled   → upcoming orders (C1c)
  └── POST /api/v1/store/orders/{id}/…      → state transitions (accept / reject / ready / cancel / propose-changes)
```

### Interceptor routing

| Path prefix | Interceptor | Header | Rejects with |
|---|---|---|---|
| `/api/v1/orders/**` | `UserIdInterceptor` | `X-User-Id` | `400` if missing/blank |
| `/api/v1/store/**` | `StoreIdInterceptor` | `X-Store-Id` | `400` if missing/blank/too long |

---

## Order Status Lifecycle

```
                ┌──────────────────────────────────────────────────────────┐
                │                     SCHEDULED                            │
                │     (future orders, held in Cache 1c ZSET)               │
                └────────────────────────┬─────────────────────────────────┘
                                         │ T-30 min window opens
                                         ▼
CHECKOUT CONFIRMED ──────────────► PENDING
                                         │
                          ┌──────────────┼──────────────┐
                          ▼             ▼               ▼
                      CONFIRMED     CANCELLED       (timeout)
                          │        (store reject)  CANCELLED
                          │
                 ┌────────┴───────────────────────────────────┐
                 ▼                                             ▼
         READY_FOR_PICKUP                     AWAITING_CUSTOMER_RESPONSE
                 │                         (store proposed item changes)
                 │                                    │
                 │                         ┌──────────┴──────────┐
                 │                         ▼                     ▼
                 │                    CONFIRMED              CANCELLED
                 │                  (customer accepted)  (customer rejected)
                 │
                 ▼
          DRIVER_ASSIGNED
                 │
                 ▼
          OUT_FOR_DELIVERY
                 │
            ┌────┴────┐
            ▼          ▼
        DELIVERED  DELIVERY_FAILED
```

### Store actions per status

| From status | Store action | Endpoint | Result |
|---|---|---|---|
| `PENDING` | Accept | `POST /accept` | → `CONFIRMED` |
| `PENDING` | Reject | `POST /reject` | → `CANCELLED` |
| `CONFIRMED` | Mark ready | `POST /ready` | → `READY_FOR_PICKUP` |
| `CONFIRMED` | Propose changes | `POST /propose-changes` | → `AWAITING_CUSTOMER_RESPONSE` |
| `CONFIRMED` | Cancel | `POST /cancel` | → `CANCELLED` |
| `AWAITING_CUSTOMER_RESPONSE` | Cancel | `POST /cancel` | → `CANCELLED` |

### Customer-facing timeline steps

The customer sees a simplified 4-step progress bar, not the internal statuses:

| Internal status | Customer step | Label |
|---|---|---|
| `PENDING` | `PLACED` | Placed |
| `CONFIRMED` / `READY_FOR_PICKUP` | `PACKED` | Packed |
| `OUT_FOR_DELIVERY` | `ON_THE_WAY` | On the way |
| `DELIVERED` | `ARRIVED` | Arrived |

`DRIVER_ASSIGNED` is internal scaffolding — it has no customer-facing timeline step.

---

## Proposal Flow

When a store discovers stock issues **after accepting** an order, it can
propose adjusted quantities to the customer instead of cancelling outright.

```
Store (CONFIRMED order)
  └── POST /api/v1/store/orders/{id}/propose-changes
        body: list of items with proposedQuantity (or null = OOS)
              │
              ▼
        Order transitions → AWAITING_CUSTOMER_RESPONSE
        Proposal written to Cache 8 (order:proposals:{orderId})
        Notification sent to customer
              │
              ▼
Customer receives push notification
  └── GET /api/v1/orders/{id}/proposed-changes
        returns: proposed quantities and OOS items
              │
              ▼
Customer responds
  └── POST /api/v1/orders/{id}/respond-to-changes
        globalDecision: ACCEPT_ALL | ACCEPT_WITH_MODIFICATIONS | CANCEL_ORDER
        itemDecisions (when ACCEPT_WITH_MODIFICATIONS):
          - ACCEPT_PROPOSED_QUANTITY
          - REQUEST_CUSTOM_QUANTITY (with customQuantity)
          - REMOVE_ITEM
              │
        ┌─────┴──────────────────────┐
        ▼                            ▼
   CONFIRMED                    CANCELLED
(order resumes)              (customer rejected)
Cache 8 deleted              Cache 8 deleted
```

**Timeout**: If the customer does not respond within the allowed window,
the order is automatically cancelled with reason `AWAITING_CUSTOMER_RESPONSE_TIMEOUT`
(handled by `ProposalTimeoutJob` — TODO).

**Store view**: While an order is in `AWAITING_CUSTOMER_RESPONSE`, the store's
kitchen card shows a "waiting for customer" badge and the `POST /ready` action
is disabled. The store can view the exact proposal they submitted via the
`pendingProposal` field on `GET /api/v1/store/orders/{orderId}`.

---

## Redis Cache Layer

All key patterns are centralised in `RedisKeys.java`. No key string is ever
constructed outside that class.

| # | Key pattern | Type | TTL | Purpose |
|---|---|---|---|---|
| **C1** | `user:active_orders:{userId}` | SET | None (managed) | Active order ID set per user. Explicitly cleared when the user's last active order completes. |
| **C1b** | `store:active_orders:{storeId}` | SET | None (managed) | Active order ID set per store. Feeds the kitchen dashboard. Cleared when order reaches DELIVERED or CANCELLED. |
| **C1c** | `store:scheduled_orders:{storeId}` | ZSET | None (managed) | Upcoming scheduled orders per store, scored by `scheduledFor` epoch ms. `getMaturing(from, to)` drives the T-30 min transition job. |
| **C2** | `order:{orderId}` | STRING / JSON | 48 h | Full static order snapshot (includes `storeId`, `storeName`). Written **once** at creation, never mutated. |
| **C3** | `order:tracking:{orderId}` | STRING / JSON | 5 min (sliding) | Live driver GPS + ETA. Expired key = driver app silent. |
| **C4** | `order:status:{orderId}` | STRING | 48 h | Current `OrderStatus` only. Kept separate from C3 so status-only reads don't load the full GPS payload. |
| **C5** | `order:lock:{cartId}` | STRING / SETNX | 30 s | Distributed creation lock per cart. Auto-released after 30 s if the holder crashes. |
| **C6** | `order:timeline:{orderId}` | LIST / JSON | 48 h | Ordered log of customer-facing transitions. RPUSH on each step; LRANGE to read. Max 4 entries. |
| **C7** | `order:verification:{orderId}` | STRING / JSON | 30 min | 2-digit delivery code generated on `order.arrived`. Short TTL — expired key means the window has closed. |
| **C8** | `order:proposals:{orderId}` | STRING / JSON | 1 h | Active change proposal while status is `AWAITING_CUSTOMER_RESPONSE`. Deleted when customer responds or order is cancelled. |

### Lifecycle invariants

- **C1, C1b, C4, C6 are always updated atomically** when a status changes.
- **C2 is never mutated** — it is the static snapshot written at creation.
- **C7 is written exactly once** per order (on `order.arrived` event) and deleted on delivery or cancellation.
- **C5 is acquired before order creation** and released after the DB write succeeds.
- **C8 is written when store submits a proposal** and deleted immediately when the customer responds or the order is cancelled.
- **C1c entries are removed** when a scheduled order enters the T-30 min window and is promoted to C1b.

### Lua scripts

| Bean | Script | Used by |
|---|---|---|
| `releaseLockScript` | Atomic lock release (GET + DEL if value matches) | `OrderLockRedisRepository` (C5) |
| `removeActiveOrderScript` | SREM + DEL if set becomes empty | `ActiveOrdersRedisRepository` (C1), `StoreActiveOrdersRedisRepository` (C1b) |

---

## Kafka

### Consumed topics

| Topic | Event | Consumer | Status |
|---|---|---|---|
| `checkout-events` | `CheckoutConfirmedEvent` | `OrderCreatedConsumer` | TODO |
| `order.arrived` | `OrderArrivedEvent` | `OrderArrivedConsumer` | TODO |

### Produced topics

| Topic | Event | Trigger |
|---|---|---|
| `order-events` | `OrderCreatedEvent` | After DB write on checkout consumed |
| `order-events` | `OrderStatusChangedEvent` | Every status transition |
| `order-events` | `OrderCancelledEvent` | Customer or store cancels |
| `order-events` | `OrderDeliveredEvent` | Final delivery confirmed |

---

## WebSocket — STOMP

The service uses **STOMP over WebSocket** (not raw WebSocket) via
`@EnableWebSocketMessageBroker`. STOMP provides topic subscriptions,
user-scoped private channels, heartbeats, and SockJS fallback out of the box.

```
Client connects:  WS /ws  (with SockJS fallback)

Client subscribes:
  /topic/orders/{orderId}/tracking   ← broadcast, one per active order
  /user/queue/orders/status          ← private channel per user (lifecycle events)
```

### Tracking push payload

Pushed whenever any of the following changes: status, GPS position, ETA, rider info, or verification code.

```json
{
  "orderId": "order-abc",
  "status": "ON_THE_WAY",
  "etaMinutes": 12,
  "driverLat": 41.0082,
  "driverLng": 28.9784,
  "rider": {
    "name": "Junho K.",
    "phone": "+90 *** ** 47"
  },
  "newTimelineEntry": {
    "status": "ON_THE_WAY",
    "label": "On the way",
    "occurredAt": "2026-06-03T15:15:00Z"
  }
}
```

On `ARRIVED` the payload also includes:

```json
{
  "status": "ARRIVED",
  "verificationCode": "47"
}
```

### Static vs live data split

| Source | Delivery mechanism |
|---|---|
| Order snapshot (items, store, address) | REST — `GET /api/v1/orders/active/{orderId}` on mount |
| Status, ETA, GPS, rider, code | WebSocket push after mount |

The tracking screen calls the REST endpoint **once** on mount, then subscribes
to the WebSocket for all subsequent updates. No polling required.

---

## REST API — Customer

All endpoints require the `X-User-Id` header (validated globally by `UserIdInterceptor`).

### Auth header

```
X-User-Id: <userId>
```

Missing or blank → `400 Bad Request`.

---

### Endpoints

#### History feed

```
GET /api/v1/orders
```

Paginated order history — **DELIVERED, CANCELLED, and SCHEDULED orders only**.
Active orders are excluded (use `/active` for those).

Each row carries an `OrderDetailRoute` discriminator the frontend uses to
determine the card layout and the detail endpoint to deep-link into.

| `route` | Card | Detail endpoint |
|---|---|---|
| `DELIVERED` | Receipt card | `GET /api/v1/orders/{orderId}` |
| `CANCELLED` | Cancellation card with refund badge | `GET /api/v1/orders/cancelled/{orderId}` |
| `SCHEDULED` | Scheduled chip card | `GET /api/v1/orders/scheduled/{orderId}` |

Query parameters (Spring `Pageable`):

| Param | Default | Description |
|---|---|---|
| `page` | `0` | Page index |
| `size` | `20` | Page size |
| `sort` | `createdAt,desc` | Sort field and direction |

Response: `Page<OrderHistoryItemResponse>`

---

#### Active orders

```
GET /api/v1/orders/active
```

Returns all in-progress orders as **static snapshots** (C1 → C2).
Live fields (status, ETA, GPS, rider) are intentionally absent — subscribe
to the WebSocket per `orderId` after receiving this list.

Response: `List<ActiveOrderItemResponse>`

---

#### Tracking screen mount

```
GET /api/v1/orders/active/{orderId}
```

Full payload for the tracking screen initial render.
Merges C2 (static snapshot) + C4 (current status) + C6 (timeline) + C7 (verification code if ARRIVED).

WebSocket delivers all subsequent live updates after mount.

Response: `ActiveOrderDetailResponse`

---

#### Scheduled order detail

```
GET /api/v1/orders/scheduled/{orderId}
```

Response: `ScheduledOrderDetailResponse`

---

#### Modify scheduled order

```
PATCH /api/v1/orders/scheduled/{orderId}
```

Updates `scheduledFor` and/or `addressId` before the modification cutoff
(`canCancel == true`). At least one field must be provided.

Returns `409 Conflict` outside the modification window.

Request: `UpdateScheduledOrderRequest`
Response: `ScheduledOrderDetailResponse` (updated state)

---

#### Cancellation receipt

```
GET /api/v1/orders/cancelled/{orderId}
```

Response: `CancelledOrderDetailResponse`

---

#### Smart order router

```
GET /api/v1/orders/{orderId}
```

Single canonical entry point for any order ID. Resolves the current status
and responds accordingly:

| Order status | Response |
|---|---|
| `DELIVERED` | `200` + `DeliveredOrderDetailResponse` |
| Active (any in-progress status) | `302` → `/api/v1/orders/active/{orderId}` |
| `CANCELLED` | `302` → `/api/v1/orders/cancelled/{orderId}` |
| `SCHEDULED` | `302` → `/api/v1/orders/scheduled/{orderId}` |
| Not found / wrong user | `404` |

Status is resolved from **C4** (O(1) Redis read) before any DB access.
`302` is used instead of `301` because status changes over the order lifetime.

---

#### Lightweight status

```
GET /api/v1/orders/{orderId}/status
```

Reads C4 only. Use for notification triggers or any caller that needs
only the current `OrderStatus` without loading the full order payload.

Response: `OrderStatusResponse`

---

#### Live GPS + ETA poll

```
GET /api/v1/orders/{orderId}/tracking
```

Reads C3 + C4. HTTP fallback for clients that cannot maintain a WebSocket
connection. Prefer WebSocket where possible.

`driverLat`, `driverLng`, and `updatedAt` are `null` when C3 has expired
(driver app silent for more than 5 minutes).

Response: `OrderTrackingResponse`

---

#### Delivery verification code

```
GET /api/v1/orders/{orderId}/verification-code
```

HTTP fallback for the 2-digit delivery code. The code is normally delivered
via WebSocket when status transitions to `ARRIVED`. Use this endpoint when
the customer's app was offline during that transition.

Reads C7 only.

| Case | Response |
|---|---|
| Code exists in C7 | `200` + `VerificationCodeResponse` |
| Code not yet generated | `404` |
| C7 TTL expired (30 min window elapsed) | `404` |

Response: `VerificationCodeResponse`

---

#### Cancel order

```
POST /api/v1/orders/{orderId}/cancel
```

Cancellable when status is:

| Status | Condition |
|---|---|
| `PENDING` | Always cancellable |
| `CONFIRMED` | Always cancellable |
| `SCHEDULED` | Only when `canCancel == true` (within modification window) |

On success: clears C1, C2, C3, C4, C6, C7.
Returns `409 Conflict` if the order has progressed past `CONFIRMED` or the
scheduled cancellation window has closed.

Response: `CancelOrderResponse`

---

#### View proposed changes

```
GET /api/v1/orders/{orderId}/proposed-changes
```

Returns the active change proposal from Cache 8.
Only meaningful when `status == AWAITING_CUSTOMER_RESPONSE`.

Shows the customer exactly which items the store could not fulfil at the
originally requested quantity, and what alternative quantities are available.
`availableQuantity == null` means the item is completely out of stock.

Response: `OrderProposedChangesResponse`

---

#### Respond to proposed changes

```
POST /api/v1/orders/{orderId}/respond-to-changes
```

Customer's decision on a pending proposal.

**Global decisions:**

| `globalDecision` | Meaning |
|---|---|
| `ACCEPT_ALL` | Accept every proposed quantity as-is |
| `ACCEPT_WITH_MODIFICATIONS` | Per-item decisions provided in `itemDecisions` |
| `CANCEL_ORDER` | Cancel the entire order |

**Per-item actions** (used with `ACCEPT_WITH_MODIFICATIONS`):

| `action` | Meaning |
|---|---|
| `ACCEPT_PROPOSED_QUANTITY` | Accept the store's proposed quantity |
| `REQUEST_CUSTOM_QUANTITY` | Request a different quantity (provide `customQuantity`) |
| `REMOVE_ITEM` | Remove this item from the order |

On resolution: Cache 8 is deleted and the order transitions back to
`CONFIRMED` (or `CANCELLED` if `CANCEL_ORDER`).

Request: `RespondToOrderChangesRequest`
Response: `RespondToOrderChangesResponse`

---

## REST API — Store

All endpoints require the `X-Store-Id` header (validated globally by `StoreIdInterceptor`).

### Auth header

```
X-Store-Id: <storeId>
```

Missing, blank, or longer than 128 characters → `400 Bad Request`.

---

### Endpoints

#### Order history

```
GET /api/v1/store/orders
```

Paginated history of **DELIVERED and CANCELLED** orders for the store.
Active orders are excluded — use `/active` or `/scheduled` for those.

Each row carries an `OrderDetailRoute` discriminator:

| `route` | Detail endpoint |
|---|---|
| `DELIVERED` | `GET /api/v1/store/orders/{orderId}` |
| `CANCELLED` | `GET /api/v1/store/orders/{orderId}` |

Response: `Page<StoreOrderHistoryItemResponse>`

---

#### Kitchen dashboard — active orders

```
GET /api/v1/store/orders/active
```

All in-progress orders for the store's kitchen display.
Source: C1b (store active SET) → C2 (order snapshot) + C4 (status).

Orders appear here as soon as they are placed (`PENDING`) and are removed
when they reach `DELIVERED` or `CANCELLED`.

**Scheduled orders are not included until 30 minutes before their requested
delivery time**, at which point they are promoted from C1c to C1b.

When an order is in `AWAITING_CUSTOMER_RESPONSE`, the card should render a
"waiting for customer" badge and disable the `POST /ready` action.

From `DRIVER_ASSIGNED` onward, the `driver` field is non-null and shows
which courier is coming to collect the order.

**Pickup verification**: store staff match the courier by comparing the order
number on the receipt with the order number shown in the courier's app.
No separate pickup code is required.

Response: `List<StoreActiveOrderItemResponse>`

---

#### Upcoming scheduled orders

```
GET /api/v1/store/orders/scheduled
```

Scheduled orders that have not yet entered the 30-minute active window.
Source: C1c (store scheduled ZSET), sorted ascending by `scheduledFor`.

Response: `List<StoreScheduledOrderItemResponse>`

---

#### Order detail

```
GET /api/v1/store/orders/{orderId}
```

Full detail view for any status — active, delivered, or cancelled.
Source: C2 + C4 + C6 for active orders; DB for historical.
Cache 8 (proposals) is merged when `status == AWAITING_CUSTOMER_RESPONSE`.

Nullable fields by status:

| Field | Non-null from |
|---|---|
| `driver` | `DRIVER_ASSIGNED` onward |
| `pendingProposal` | `AWAITING_CUSTOMER_RESPONSE` only |
| `cancellation` | `CANCELLED` only |

Response: `StoreOrderDetailResponse`

---

#### Order status

```
GET /api/v1/store/orders/{orderId}/status
```

Lightweight status read — C4 only.

Response: `StoreOrderStatusResponse`

---

#### Accept order

```
POST /api/v1/store/orders/{orderId}/accept
```

Transitions `PENDING → CONFIRMED`.
Returns `409 Conflict` if the order is not in `PENDING` status.

Response: `StoreAcceptOrderResponse`

---

#### Reject order

```
POST /api/v1/store/orders/{orderId}/reject
```

Pre-acceptance refusal — **`PENDING` status only**.
Transitions `PENDING → CANCELLED`.

For out-of-stock rejections, the request body should list the affected items
(with `requestedQuantity`, `unit`, and `availableQuantity`) so the
cancellation notification can show the customer exactly what was unavailable.

Request body:

```json
{
  "reason": "OUT_OF_STOCK",
  "outOfStockItems": [
    {
      "productId": "prod-123",
      "productName": "Organic Apple",
      "requestedQuantity": 3.0,
      "unit": "KG",
      "availableQuantity": null
    }
  ]
}
```

`availableQuantity == null` means completely unavailable.
`outOfStockItems` is required only when `reason == OUT_OF_STOCK`.

Returns `409 Conflict` if the order is not in `PENDING` status.

Request: `RejectOrderRequest`
Response: `StoreRejectOrderResponse`

---

#### Mark ready for pickup

```
POST /api/v1/store/orders/{orderId}/ready
```

Transitions `CONFIRMED → READY_FOR_PICKUP`.
Returns `409 Conflict` if the order is not in `CONFIRMED` status.

Response: `StoreReadyOrderResponse`

---

#### Cancel order (post-acceptance)

```
POST /api/v1/store/orders/{orderId}/cancel
```

Post-acceptance cancellation — covers **`CONFIRMED` and `AWAITING_CUSTOMER_RESPONSE`** statuses.

Use `POST /reject` for pre-acceptance (PENDING). This endpoint is for cases
where the store accepted but later determined it cannot fulfil the order
(e.g. store closing early, unable to fulfil after preparation started).

Allowed reasons:

| `reason` | When to use |
|---|---|
| `STORE_CLOSED` | Store is closing unexpectedly |
| `STORE_UNABLE_TO_FULFIL` | Operational issue during preparation |

Returns `409 Conflict` if the order is not in `CONFIRMED` or `AWAITING_CUSTOMER_RESPONSE`.

Request: `StoreCancelOrderRequest`
Response: `StoreCancelOrderResponse`

---

#### Propose item changes

```
POST /api/v1/store/orders/{orderId}/propose-changes
```

Propose adjusted quantities for one or more items — **`CONFIRMED` status only**.
Used when the store discovers stock issues during preparation.

The request body is a list of affected items. Each entry must include:
- `requestedQuantity` — what the customer originally ordered
- `unit` — unit of measure
- `availableQuantity` — what the store can provide (`null` = completely out of stock)

Transitions `CONFIRMED → AWAITING_CUSTOMER_RESPONSE`.
Writes the proposal to Cache 8 (`order:proposals:{orderId}`, TTL 1 h).

Returns `409 Conflict` if the order is not in `CONFIRMED` status.

Request: `ProposeOrderChangesRequest`
Response: `StoreProposeOrderChangesResponse`

---

## Response DTOs

### Shared sub-types (`customer/dto/response/shared/`)

| DTO | Fields |
|---|---|
| `DeliveryAddressDto` | `street`, `city`, `lat`, `lng` |
| `StoreLocationDto` | `lat`, `lng` |
| `OrderItemDto` | `productId`\*, `name`, `imageUrl`, `quantity`, `unitPrice`, `subtotal` |
| `PricingDto` | `itemsSubtotal`, `deliveryFee`, `serviceFee`, `promoDiscount`, `grandTotal` |

\* `productId` is `null` for past-order receipt views; non-null for active order detail.

### Store shared sub-types (`store/dto/response/shared/`)

| DTO | Fields |
|---|---|
| `CustomerInfoDto` | `displayName` (masked, e.g. `"Serdar A."`) |

### Customer response types

| DTO | Endpoint | Source |
|---|---|---|
| `OrderHistoryItemResponse` | `GET /api/v1/orders` | DB |
| `ActiveOrderItemResponse` | `GET /api/v1/orders/active` | C1 → C2 |
| `ActiveOrderDetailResponse` | `GET /api/v1/orders/active/{orderId}` | C2 + C4 + C6 + C7 |
| `ScheduledOrderDetailResponse` | `GET /api/v1/orders/scheduled/{orderId}` | DB |
| `CancelledOrderDetailResponse` | `GET /api/v1/orders/cancelled/{orderId}` | DB |
| `DeliveredOrderDetailResponse` | `GET /api/v1/orders/{orderId}` | DB |
| `OrderStatusResponse` | `GET /api/v1/orders/{orderId}/status` | C4 |
| `OrderTrackingResponse` | `GET /api/v1/orders/{orderId}/tracking` | C3 + C4 |
| `VerificationCodeResponse` | `GET /api/v1/orders/{orderId}/verification-code` | C7 |
| `CancelOrderResponse` | `POST /api/v1/orders/{orderId}/cancel` | — |
| `OrderProposedChangesResponse` | `GET /api/v1/orders/{orderId}/proposed-changes` | C8 |
| `RespondToOrderChangesResponse` | `POST /api/v1/orders/{orderId}/respond-to-changes` | — |
| `TimelineStepResponse` | embedded in detail responses | C6 / DB |

### Store response types

| DTO | Endpoint | Source |
|---|---|---|
| `StoreOrderHistoryItemResponse` | `GET /api/v1/store/orders` | DB |
| `StoreActiveOrderItemResponse` | `GET /api/v1/store/orders/active` | C1b → C2 + C4 |
| `StoreScheduledOrderItemResponse` | `GET /api/v1/store/orders/scheduled` | C1c |
| `StoreOrderDetailResponse` | `GET /api/v1/store/orders/{orderId}` | C2 + C4 + C6 + C8 / DB |
| `StoreOrderStatusResponse` | `GET /api/v1/store/orders/{orderId}/status` | C4 |
| `StoreAcceptOrderResponse` | `POST /api/v1/store/orders/{orderId}/accept` | — |
| `StoreRejectOrderResponse` | `POST /api/v1/store/orders/{orderId}/reject` | — |
| `StoreReadyOrderResponse` | `POST /api/v1/store/orders/{orderId}/ready` | — |
| `StoreCancelOrderResponse` | `POST /api/v1/store/orders/{orderId}/cancel` | — |
| `StoreProposeOrderChangesResponse` | `POST /api/v1/store/orders/{orderId}/propose-changes` | — |

### Timeline steps (always 4, fixed order)

| Step | Label | `occurredAt` |
|---|---|---|
| `PLACED` | Placed | Timestamp or `null` (pending) |
| `PACKED` | Packed | Timestamp or `null` (pending) |
| `ON_THE_WAY` | On the way | Timestamp or `null` (pending) |
| `ARRIVED` | Arrived | Timestamp or `null` (pending) |

---

## Shared Enums

All domain enums live in `shared/enums/` to avoid duplication across customer
and store DTOs. Never define order domain enums as nested types inside DTOs.

### `OrderStatus`

| Value | Description |
|---|---|
| `SCHEDULED` | Future order, held in C1c until T-30 min window |
| `PENDING` | Placed and awaiting store acceptance |
| `CONFIRMED` | Accepted by the store |
| `AWAITING_CUSTOMER_RESPONSE` | Store proposed item changes; paused until customer decides |
| `READY_FOR_PICKUP` | Prepared and waiting for courier |
| `DRIVER_ASSIGNED` | Courier assigned and en route to store |
| `OUT_FOR_DELIVERY` | Courier picked up the order |
| `ARRIVED` | Courier reached the delivery address |
| `DELIVERED` | Delivery verified and complete |
| `DELIVERY_FAILED` | Delivery attempt failed |
| `CANCELLED` | Order cancelled by user, store, or system |

### `OrderCancelledBy`

`USER` · `STORE` · `SYSTEM`

### `OrderCancellationReason`

| Value | Triggered by |
|---|---|
| `CUSTOMER_REQUESTED` | Customer cancelled |
| `STORE_REJECTED` | Store rejected at PENDING |
| `STORE_CLOSED` | Store closed unexpectedly |
| `STORE_UNABLE_TO_FULFIL` | Operational failure post-acceptance |
| `NO_RIDERS_AVAILABLE` | No couriers available for dispatch |
| `STORE_RESPONSE_TIMEOUT` | Store did not accept/reject within the timeout |
| `AWAITING_CUSTOMER_RESPONSE_TIMEOUT` | Customer did not respond to proposal within 1 hour |
| `SYSTEM_ERROR` | Internal error |

### `RefundStatus`

`REFUND_PENDING` · `REFUNDED`

### `ProductUnit`

`PCS` · `KG` · `GRAM` · `LITER` · `ML` · `PACK`

Mirrored from cart-service — keep in sync.

### `ScheduleType`

`IMMEDIATE` · `SCHEDULED`

Mirrored from cart-service — keep in sync.

---

## Package Structure

```
com.sebet.order_service/
├── OrderServiceApplication.java
│
├── config/
│   ├── UserIdInterceptor.java          X-User-Id header guard (/api/v1/orders/**)
│   ├── StoreIdInterceptor.java         X-Store-Id header guard (/api/v1/store/**)
│   └── WebMvcConfig.java               Registers both interceptors on separate paths
│
├── cache/
│   ├── config/
│   │   └── OrderRedisConfig.java       Lua scripts: releaseLockScript, removeActiveOrderScript
│   ├── dto/
│   │   ├── DeliveryAddress.java
│   │   ├── DriverInfo.java
│   │   ├── OrderItem.java
│   │   ├── OrderProposalsCacheDto.java  C8 — change proposals
│   │   ├── OrderTimelineEntry.java     C6 list element
│   │   ├── RedisOrder.java             C2 static snapshot (includes storeId, storeName)
│   │   ├── RedisOrderTracking.java     C3 live GPS + ETA
│   │   ├── StoreLocation.java
│   │   └── VerificationCodeCacheDto.java  C7 verification code
│   ├── keys/
│   │   └── RedisKeys.java              Central registry of all key patterns (C1–C8)
│   └── repository/
│       ├── ActiveOrdersRedisRepository.java         C1 — user active SET
│       ├── StoreActiveOrdersRedisRepository.java    C1b — store active SET
│       ├── StoreScheduledOrdersRedisRepository.java C1c — store scheduled ZSET
│       ├── OrderRedisRepository.java                C2
│       ├── OrderTrackingRedisRepository.java        C3
│       ├── OrderStatusRedisRepository.java          C4
│       ├── OrderLockRedisRepository.java            C5
│       ├── OrderTimelineRedisRepository.java        C6
│       ├── VerificationCodeRedisRepository.java     C7
│       └── OrderProposalsRedisRepository.java       C8
│
├── shared/
│   └── enums/
│       ├── OrderStatus.java
│       ├── OrderCancelledBy.java
│       ├── OrderCancellationReason.java
│       ├── RefundStatus.java
│       ├── ProductUnit.java
│       └── ScheduleType.java
│
├── customer/
│   ├── controller/
│   │   └── CustomerOrderController.java
│   └── dto/
│       ├── request/
│       │   ├── RespondToOrderChangesRequest.java
│       │   └── UpdateScheduledOrderRequest.java
│       └── response/
│           ├── shared/
│           │   ├── DeliveryAddressDto.java
│           │   ├── OrderItemDto.java
│           │   ├── PricingDto.java
│           │   └── StoreLocationDto.java
│           ├── ActiveOrderDetailResponse.java
│           ├── ActiveOrderItemResponse.java
│           ├── CancelledOrderDetailResponse.java
│           ├── CancelOrderResponse.java
│           ├── DeliveredOrderDetailResponse.java
│           ├── OrderHistoryItemResponse.java
│           ├── OrderProposedChangesResponse.java
│           ├── OrderStatusResponse.java
│           ├── OrderTrackingResponse.java
│           ├── RespondToOrderChangesResponse.java
│           ├── ScheduledOrderDetailResponse.java
│           ├── TimelineStepResponse.java
│           └── VerificationCodeResponse.java
│
└── store/
    ├── controller/
    │   └── StoreOrderController.java
    └── dto/
        ├── request/
        │   ├── ProposeOrderChangesRequest.java
        │   ├── RejectOrderRequest.java
        │   └── StoreCancelOrderRequest.java
        └── response/
            ├── shared/
            │   └── CustomerInfoDto.java
            ├── StoreAcceptOrderResponse.java
            ├── StoreActiveOrderItemResponse.java
            ├── StoreCancelOrderResponse.java
            ├── StoreOrderDetailResponse.java
            ├── StoreOrderHistoryItemResponse.java
            ├── StoreOrderStatusResponse.java
            ├── StoreProposeOrderChangesResponse.java
            ├── StoreReadyOrderResponse.java
            ├── StoreRejectOrderResponse.java
            └── StoreScheduledOrderItemResponse.java
```

---

## Pending / TODOs

The following are **designed and documented** but not yet implemented.
Each item references the relevant cache keys and event contracts above.

### Kafka consumers

| # | Consumer | Topic | What it does |
|---|---|---|---|
| 1 | `OrderCreatedConsumer` | `checkout-events` | Consumes `CheckoutConfirmedEvent` → creates DB record → writes C1, C1b (or C1c if SCHEDULED), C2, C4, C6 (PLACED) |
| 2 | `OrderArrivedConsumer` | `order.arrived` | Generates 2-digit code → writes DB + C7 → updates C4 + C6 (ARRIVED) → pushes WebSocket |

### Background jobs

| # | Job | What it does |
|---|---|---|
| 1 | `ScheduledOrderTransitionJob` | Runs periodically; reads C1c `getMaturing(now-30m, now)` → promotes maturing scheduled orders from C1c to C1b; transitions status to `PENDING` |
| 2 | `ProposalTimeoutJob` | Scans for orders stuck in `AWAITING_CUSTOMER_RESPONSE` past the 1-hour C8 TTL → cancels with reason `AWAITING_CUSTOMER_RESPONSE_TIMEOUT` |

### Driver endpoints

| # | Endpoint | What it does |
|---|---|---|
| 1 | `POST /api/v1/driver/orders/{orderId}/verify-code` | Validates the 2-digit code → `markVerified()` on C7 → sets `codeVerifiedAt` on DB → transitions order to `DELIVERED` |

### Service layer

All controller methods currently throw `UnsupportedOperationException`.
Service classes, repository interfaces, and Flyway DB migrations are pending.

### DB schema (Flyway migrations pending)

Order table requires these columns:

```sql
-- order lifecycle
verification_code     VARCHAR(2)
code_generated_at     TIMESTAMPTZ
code_verified_at      TIMESTAMPTZ

-- proposal tracking
proposal_submitted_at TIMESTAMPTZ
proposal_resolved_at  TIMESTAMPTZ

-- cancellation detail
cancelled_by          VARCHAR(16)     -- USER | STORE | SYSTEM
cancellation_reason   VARCHAR(64)

-- refund
refund_status         VARCHAR(32)
refunded_at           TIMESTAMPTZ

-- order status history (separate table)
CREATE TABLE order_status_history (
  id           UUID PRIMARY KEY,
  order_id     UUID NOT NULL,
  status       VARCHAR(32) NOT NULL,
  occurred_at  TIMESTAMPTZ NOT NULL,
  triggered_by VARCHAR(16) NOT NULL  -- USER | STORE | SYSTEM | DRIVER
);
```

### WebSocket broker

`@EnableWebSocketMessageBroker` configuration, `SimpMessagingTemplate` wiring,
and SockJS fallback are pending.

### Error responses

A standard `ErrorResponse` DTO (`code`, `message`, `timestamp`) and a global
`@ControllerAdvice` exception handler are pending.
