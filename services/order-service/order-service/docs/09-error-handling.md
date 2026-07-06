# Error Handling

## Error Response Shape

All API errors return a consistent JSON body:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "subtotalAmount must be >= 0",
  "timestamp": "2026-07-06T10:00:00.000Z"
}
```

Implemented in `shared/exception/ErrorResponse.java` (Java record).

## Global Exception Handler

`GlobalExceptionHandler` (`shared/exception/GlobalExceptionHandler.java`) is a `@RestControllerAdvice` that maps exceptions to HTTP responses.

| Exception | HTTP status | Error code |
|---|---|---|
| `IllegalArgumentException` | 400 | `VALIDATION_ERROR` |
| `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` |
| `ConstraintViolationException` | 400 | `VALIDATION_ERROR` |
| `HttpMessageNotReadableException` | 400 | `MALFORMED_REQUEST` |
| `MissingRequestHeaderException` | 400 | `MISSING_HEADER` |
| `NoResourceFoundException` | 404 | `NOT_FOUND` |
| `UnsupportedOperationException` | 501 | `NOT_IMPLEMENTED` |
| `Exception` (fallback) | 500 | `INTERNAL_ERROR` |

Unhandled exceptions are logged at ERROR level before returning `INTERNAL_ERROR`.

## Status Code Rules

- `400 Bad Request`: invalid request body, failed bean validation, or missing/blank identity header.
- `404 Not Found`: no handler matched the requested path.
- `501 Not Implemented`: endpoint contract exists but business logic is not yet built.
- `500 Internal Server Error`: unexpected server failure.

Planned (not yet enforced by handler):

- `403 Forbidden`: store does not own the order.
- `409 Conflict`: invalid lifecycle transition or modification/cancellation cutoff passed.
- `503 Service Unavailable`: downstream/event infrastructure unavailable where retry is appropriate.

## Input Validation

Amount fields (`subtotalAmount`, `itemDiscountAmount`, `orderDiscountAmount`, `deliveryFeeAmount`, `totalAmount`) are validated as `>= 0` in the compact constructors of `CheckoutConfirmedEvent` and `CreateOrderCommand`. Invalid values throw `IllegalArgumentException`, which the global handler maps to `400 VALIDATION_ERROR`.

`deliveryAddressJson` is validated as parseable JSON in `OrderCreationService.createNewOrder()` before any database write. An unparseable value throws `IllegalArgumentException`, preventing a later `IllegalStateException` in `OrderCreationRedisWriter`.

## Interceptor Errors

Interceptors reject missing or invalid identity headers before reaching the exception handler:

- customer endpoints (`/api/v1/orders/**`): `X-User-Id`
- store endpoints (`/api/v1/store/**`): `X-Store-Id`

These call `response.sendError(400, ...)` directly and bypass `GlobalExceptionHandler`.

## Design Rule

Do not expose Redis, database, Kafka, or serialization internals directly to clients. Convert internal failures into stable API error codes.
