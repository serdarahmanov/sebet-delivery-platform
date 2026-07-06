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

## Current Implementation Status

The controllers define request mappings and DTO contracts, but every endpoint currently throws:

```text
UnsupportedOperationException("Not implemented yet")
```

Treat the endpoints as API design contracts until service methods are implemented.

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
- `400 Bad Request`: missing or invalid required identity header.
- `403 Forbidden`: store attempts to access an order it does not own.
- `404 Not Found`: order or proposal/code not found.
- `409 Conflict`: invalid lifecycle transition or modification outside allowed window.
