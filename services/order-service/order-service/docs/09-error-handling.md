# Error Handling

## Current State

Controllers currently throw `UnsupportedOperationException("Not implemented yet")`.

There is no global `@ControllerAdvice` or standard error DTO in this service yet.

## Planned Error Response

Use a consistent response shape such as:

```json
{
  "code": "ORDER_NOT_FOUND",
  "message": "Order not found",
  "timestamp": "2026-07-04T12:00:00Z"
}
```

## Planned Status Code Rules

- `400 Bad Request`: missing/blank identity header or invalid request body.
- `403 Forbidden`: store does not own the order.
- `404 Not Found`: order, proposal, tracking state, or verification code does not exist.
- `409 Conflict`: invalid lifecycle transition or modification/cancellation cutoff passed.
- `500 Internal Server Error`: unexpected server failure.
- `503 Service Unavailable`: downstream/event infrastructure unavailable where retry is appropriate.

## Interceptor Errors

Implemented interceptors reject missing or invalid identity headers for:

- customer endpoints: `X-User-Id`
- store endpoints: `X-Store-Id`

## Design Rule

Do not expose Redis, database, Kafka, or serialization internals directly to clients. Convert internal failures into stable API error codes.
