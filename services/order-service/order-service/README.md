# Order Service

Manages the full lifecycle of customer orders in the Sebet delivery platform —
from checkout confirmation through live tracking to delivery verification.

---

## Table of Contents

1. [Tech Stack](#tech-stack)
2. [Architecture Overview](#architecture-overview)
3. [Order Status Lifecycle](#order-status-lifecycle)
4. [Redis Cache Layer](#redis-cache-layer)
5. [Kafka](#kafka)
6. [WebSocket — STOMP](#websocket--stomp)
7. [REST API — Customer](#rest-api--customer)
8. [Response DTOs](#response-dtos)
9. [Package Structure](#package-structure)
10. [Pending / TODOs](#pending--todos)

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
- **WebSocket (STOMP)** for pushing live updates to the customer's tracking screen
- **Cache-first reads** — hot data is always served from Redis; the DB is the fallback

```
cart-service
  └── Kafka: checkout-events
        └── OrderCreatedConsumer (TODO)
              ├── writes DB
              ├── writes Cache 2 (order snapshot)
              ├── writes Cache 1 (active order set)
              ├── writes Cache 4 (status)
              └── writes Cache 6 (timeline: PLACED)

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
```

---

## Order Status Lifecycle

```
                    ┌─────────────────────────────────────────────────────┐
                    │                  SCHEDULED                          │
                    │         (future orders, time-locked)                │
                    └───────────────────┬─────────────────────────────────┘
                                        │ window opens
                                        ▼
CHECKOUT CONFIRMED ──────────────► PENDING
                                        │
                         ┌──────────────┼──────────────┐
                         ▼             ▼               ▼
                     CONFIRMED    CANCELLED       (timeout)
                         │                        CANCELLED
                         ▼
                  READY_FOR_PICKUP
                         │
                         ▼
                  DRIVER_ASSIGNED
                         │
                         ▼
                  OUT_FOR_DELIVERY
                         │
                    ┌────┴────┐
                    ▼         ▼
                DELIVERED  DELIVERY_FAILED
```

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

## Redis Cache Layer

All key patterns are centralised in `RedisKeys.java`. No key string is ever
constructed outside that class.

| # | Key pattern | Type | TTL | Purpose |
|---|---|---|---|---|
| **C1** | `user:active_orders:{userId}` | SET | None (managed) | Active order ID set per user. No TTL — keys are explicitly removed when a user's last active order completes. |
| **C2** | `order:{orderId}` | STRING / JSON | 48 h | Full static order snapshot. Written **once** at order creation, never mutated. Serves tracking screen mount and active-orders list. |
| **C3** | `order:tracking:{orderId}` | STRING / JSON | 5 min (sliding) | Live driver GPS + ETA, written every few seconds by the delivery service. Expired key = driver app silent. |
| **C4** | `order:status:{orderId}` | STRING | 48 h | Current status string only. Kept separate from C3 so status-only reads (notification triggers, status endpoint) don't load the full GPS payload. |
| **C5** | `order:lock:{cartId}` | STRING / SETNX | 30 s | Distributed lock preventing concurrent order creation from the same cart. Auto-released after 30 s if the holder crashes. |
| **C6** | `order:timeline:{orderId}` | LIST / JSON | 48 h | Ordered log of customer-facing status transitions with timestamps. RPUSH on each transition; LRANGE to read. Max 4 entries per order. |
| **C7** | `order:verification:{orderId}` | STRING / JSON | 30 min | 2-digit delivery verification code generated on `order.arrived`. Short TTL — expired key means the delivery window has closed. |

### Lifecycle invariants

- **C1 + C4 + C6 are always updated atomically** when a status changes.
- **C2 is never mutated** — it is the static snapshot written at creation.
- **C7 is written exactly once** per order (on `order.arrived` event) and deleted on delivery or cancellation.
- **C5 is acquired before order creation** and released after the DB write succeeds.

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
only the status string without loading the full order payload.

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

## Response DTOs

### Shared sub-types (`customer/dto/response/shared/`)

| DTO | Fields |
|---|---|
| `DeliveryAddressDto` | `street`, `city`, `lat`, `lng` |
| `StoreLocationDto` | `lat`, `lng` |
| `OrderItemDto` | `productId`\*, `name`, `imageUrl`, `quantity`, `unitPrice`, `subtotal` |
| `PricingDto` | `itemsSubtotal`, `deliveryFee`, `serviceFee`, `promoDiscount`, `grandTotal` |

\* `productId` is `null` for past-order receipt views; non-null for active order detail.

### Response types

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
| `TimelineStepResponse` | embedded in detail responses | C6 / DB |

### Timeline steps (always 4, fixed order)

| Step | Label | `occurredAt` |
|---|---|---|
| `PLACED` | Placed | Timestamp or `null` (pending) |
| `PACKED` | Packed | Timestamp or `null` (pending) |
| `ON_THE_WAY` | On the way | Timestamp or `null` (pending) |
| `ARRIVED` | Arrived | Timestamp or `null` (pending) |

### `OrderDetailRoute` discriminator

Carried on every `OrderHistoryItemResponse` row.

| Value | Card type | Detail endpoint |
|---|---|---|
| `DELIVERED` | Receipt | `GET /api/v1/orders/{orderId}` |
| `CANCELLED` | Cancellation + refund badge | `GET /api/v1/orders/cancelled/{orderId}` |
| `SCHEDULED` | Scheduled chip | `GET /api/v1/orders/scheduled/{orderId}` |

---

## Package Structure

```
com.sebet.order_service/
├── OrderServiceApplication.java
│
├── config/
│   ├── UserIdInterceptor.java          X-User-Id header guard (all /api/** routes)
│   └── WebMvcConfig.java
│
├── cache/
│   ├── config/
│   │   └── OrderRedisConfig.java       Lua scripts (lock release, active-order removal)
│   ├── dto/
│   │   ├── DeliveryAddress.java
│   │   ├── DriverInfo.java
│   │   ├── OrderItem.java
│   │   ├── OrderTimelineEntry.java     C6 list element
│   │   ├── RedisOrder.java             C2 static snapshot
│   │   ├── RedisOrderTracking.java     C3 live GPS + ETA
│   │   ├── StoreLocation.java
│   │   └── VerificationCodeCacheDto.java  C7 verification code
│   ├── keys/
│   │   └── RedisKeys.java              Central registry of all key patterns
│   └── repository/
│       ├── ActiveOrdersRedisRepository.java    C1
│       ├── OrderRedisRepository.java           C2
│       ├── OrderTrackingRedisRepository.java   C3
│       ├── OrderStatusRedisRepository.java     C4
│       ├── OrderLockRedisRepository.java       C5
│       ├── OrderTimelineRedisRepository.java   C6
│       └── VerificationCodeRedisRepository.java C7
│
└── customer/
    ├── controller/
    │   └── CustomerOrderController.java
    └── dto/
        ├── request/
        │   └── UpdateScheduledOrderRequest.java
        └── response/
            ├── shared/
            │   ├── DeliveryAddressDto.java
            │   ├── OrderItemDto.java
            │   ├── PricingDto.java
            │   └── StoreLocationDto.java
            ├── ActiveOrderDetailResponse.java
            ├── ActiveOrderItemResponse.java
            ├── CancelledOrderDetailResponse.java
            ├── CancelOrderResponse.java
            ├── DeliveredOrderDetailResponse.java
            ├── OrderHistoryItemResponse.java
            ├── OrderStatusResponse.java
            ├── OrderTrackingResponse.java
            ├── ScheduledOrderDetailResponse.java
            ├── TimelineStepResponse.java
            └── VerificationCodeResponse.java
```

---

## Pending / TODOs

The following are **designed and documented** but not yet implemented.
Each item references the relevant cache keys and event contracts above.

### Kafka consumers

| # | Consumer | Topic | What it does |
|---|---|---|---|
| 1 | `OrderCreatedConsumer` | `checkout-events` | Consumes `CheckoutConfirmedEvent` → creates DB record → writes C1, C2, C4, C6 (PLACED) |
| 2 | `OrderArrivedConsumer` | `order.arrived` | Generates 2-digit code → writes DB + C7 → updates C4 + C6 (ARRIVED) → pushes WebSocket |

### Driver endpoints

| # | Endpoint | What it does |
|---|---|---|
| 1 | `POST /api/v1/driver/orders/{orderId}/verify-code` | Validates the 2-digit code → `markVerified()` on C7 → sets `codeVerifiedAt` on DB → transitions order to DELIVERED |

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

### Store & driver controllers

Store-side endpoints (accept / reject / ready) and full driver endpoints
(assign / pickup / deliver / fail) are pending.
