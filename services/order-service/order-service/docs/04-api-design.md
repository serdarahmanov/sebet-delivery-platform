# API Design

## Customer API

Base path:

```text
/api/v1/orders
```

Required header:

```http
X-User-Id: <user-id>
```

Endpoint groups:

- `GET /api/v1/orders`
- `GET /api/v1/orders/active`
- `GET /api/v1/orders/active/{orderId}`
- `GET /api/v1/orders/scheduled/{orderId}`
- `PATCH /api/v1/orders/scheduled/{orderId}`
- `GET /api/v1/orders/cancelled/{orderId}`
- `GET /api/v1/orders/{orderId}`
- `GET /api/v1/orders/{orderId}/status`
- `GET /api/v1/orders/{orderId}/tracking`
- `GET /api/v1/orders/{orderId}/verification-code`
- `GET /api/v1/orders/{orderId}/proposed-changes`
- `POST /api/v1/orders/{orderId}/respond-to-changes`
- `POST /api/v1/orders/{orderId}/cancel`

## Store API

Base path:

```text
/api/v1/store/orders
```

Required header:

```http
X-Store-Id: <store-id>
```

Endpoint groups:

- `GET /api/v1/store/orders`
- `GET /api/v1/store/orders/active`
- `GET /api/v1/store/orders/scheduled`
- `GET /api/v1/store/orders/{orderId}`
- `GET /api/v1/store/orders/{orderId}/status`
- `POST /api/v1/store/orders/{orderId}/accept`
- `POST /api/v1/store/orders/{orderId}/reject`
- `POST /api/v1/store/orders/{orderId}/ready`
- `POST /api/v1/store/orders/{orderId}/cancel`
- `POST /api/v1/store/orders/{orderId}/propose-changes`

## Driver API

Base path:

```text
/api/v1/driver/orders
```

Required header:

```http
X-Driver-Id: <driver-id>
```

Endpoint groups:

- `GET  /api/v1/driver/orders/{orderId}`           — delivery detail (C2 + C4)
- `POST /api/v1/driver/orders/{orderId}/pickup`    — READY_FOR_PICKUP → OUT_FOR_DELIVERY
- `POST /api/v1/driver/orders/{orderId}/arrive`    — OUT_FOR_DELIVERY → ARRIVED; generates verification code → C7
- `POST /api/v1/driver/orders/{orderId}/complete`  — ARRIVED → DELIVERED; validates code from C7
- `POST /api/v1/driver/orders/{orderId}/decline`   — unassigns driver (status unchanged); valid only before OUT_FOR_DELIVERY

GPS and ETA updates do not go through this API. The driver app sends coordinates
to the tracking service, which publishes a `DriverLocationUpdatedEvent`. Order-service
consumes that event, updates Cache 3 (`movementStatus`, `driverLat`, `driverLng`,
`etaMinutes`), and pushes a WebSocket message to the customer.

## Internal API

Base path:

```text
/api/v1/internal/orders
```

Required header:

```http
X-Internal-Key: <shared-secret>
```

Not exposed to customers, stores, or drivers. Intended for the dispatch service,
internal background job triggers (via admin/ops), and other platform services.

Endpoint groups:

- `POST /api/v1/internal/orders/{orderId}/assign-driver`      — sets driverId + driverAssignedAt (dispatch)
- `POST /api/v1/internal/orders/{orderId}/unassign-driver`    — clears driverId with no replacement; valid on any non-terminal status
- `POST /api/v1/internal/orders/{orderId}/system-cancel`      — system-initiated cancellation
- `POST /api/v1/internal/orders/{orderId}/activate-scheduled` — SCHEDULED → PENDING
- `POST /api/v1/internal/orders/{orderId}/cancel-proposal`    — AWAITING_CUSTOMER_RESPONSE → CANCELLED

## Current Implementation Status

### Customer API — GET endpoints (implemented)

The following customer read endpoints are fully implemented in `CustomerOrderQueryService`:

| Endpoint | Source | Status |
|---|---|---|
| `GET /api/v1/orders` | DB | ✅ implemented |
| `GET /api/v1/orders/active` | C1 → C2 | ✅ implemented |
| `GET /api/v1/orders/active/{orderId}` | C2 + C4 + C6 + C7 | ✅ implemented |
| `GET /api/v1/orders/scheduled/{orderId}` | DB | ✅ implemented |
| `GET /api/v1/orders/cancelled/{orderId}` | DB | ✅ implemented |
| `GET /api/v1/orders/{orderId}` | DB (smart router) | ✅ implemented |
| `GET /api/v1/orders/{orderId}/status` | C4 → DB | ✅ implemented |
| `GET /api/v1/orders/{orderId}/tracking` | C4 + C3 | ✅ implemented |
| `GET /api/v1/orders/{orderId}/verification-code` | C7 | ✅ implemented |
| `GET /api/v1/orders/{orderId}/proposed-changes` | C8 | ✅ implemented |

### Customer API — write endpoints (pending)

The following customer write endpoints still throw `UnsupportedOperationException`:

- `PATCH /api/v1/orders/scheduled/{orderId}` — modify scheduled order
- `POST  /api/v1/orders/{orderId}/respond-to-changes` — respond to store proposal
- `POST  /api/v1/orders/{orderId}/cancel` — cancel order

### All other APIs (pending)

Store, driver, and internal controllers define DTO contracts but all endpoints throw:

```text
UnsupportedOperationException("Not implemented yet")
```

## Response Design

Customer and store response DTOs are intentionally separate.

Shared subtypes exist for repeated response fragments such as:

- customer info
- delivery address
- order item
- pricing
- store location

## Status Code Intent

Planned status code conventions:

- `200 OK`: successful reads and actions with response body.
- `302 Found`: smart customer order router redirects to status-specific detail endpoints.
- `400 Bad Request`: missing or invalid required identity header (`X-User-Id`, `X-Store-Id`, `X-Driver-Id`).
- `401 Unauthorized`: missing `X-Internal-Key` header on internal endpoints.
- `403 Forbidden`: invalid `X-Internal-Key` value; or store attempts to access an order it does not own.
- `404 Not Found`: order or proposal/code not found.
- `409 Conflict`: invalid lifecycle transition or modification outside allowed window.
