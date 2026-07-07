# Error Handling

## Error Response Shape

Controller-handled API errors return a consistent JSON body:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "subtotalAmount must be >= 0",
  "timestamp": "2026-07-06T10:00:00.000Z"
}
```

Implemented in `shared/exception/ErrorResponse.java` (Java record). MVC interceptor failures are sent with `response.sendError(...)` before controller execution and do not use this JSON body.

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
| `OrderNotFoundException` | 404 | `ORDER_NOT_FOUND` |
| `DriverNotAssignedException` | 403 | `DRIVER_NOT_ASSIGNED` |
| `VerificationCodeNotFoundException` | 404 | `VERIFICATION_CODE_NOT_FOUND` |
| `OrderInvalidTransitionException` | 409 | `ORDER_INVALID_TRANSITION` |
| `OptimisticLockingFailureException` | 409 | `ORDER_INVALID_TRANSITION` |
| `UnsupportedOperationException` | 501 | `NOT_IMPLEMENTED` |
| `Exception` (fallback) | 500 | `INTERNAL_ERROR` |

Unhandled exceptions are logged at ERROR level before returning `INTERNAL_ERROR`.

## Status Code Rules

- `400 Bad Request`: invalid request body, failed bean validation, missing/blank identity header, or submitted verification code does not match stored code.
- `403 Forbidden`: invalid `X-Internal-Key` value, or `X-Driver-Id` does not match the driver assigned to the order.
- `404 Not Found`: no handler matched the requested path, the order does not exist, order ownership should be hidden, or verification code not found in Redis or DB.
- `409 Conflict`: invalid lifecycle transition or stale concurrent lifecycle update.
- `501 Not Implemented`: endpoint contract exists but business logic is not yet built.
- `500 Internal Server Error`: unexpected server failure.

Planned (not yet enforced by handler):

- `409 Conflict`: modification/cancellation cutoff passed.
- `503 Service Unavailable`: downstream/event infrastructure unavailable where retry is appropriate.

## Input Validation

Amount fields (`subtotalAmount`, `itemDiscountAmount`, `orderDiscountAmount`, `deliveryFeeAmount`, `totalAmount`) are validated as `>= 0` in the compact constructors of `CheckoutConfirmedEvent` and `CreateOrderCommand`. Invalid values throw `IllegalArgumentException`, which the global handler maps to `400 VALIDATION_ERROR`.

`deliveryAddressJson` is validated as parseable JSON in `OrderCreationService.createNewOrder()` before any database write. An unparseable value throws `IllegalArgumentException`, preventing a later `IllegalStateException` in `OrderCreationRedisWriter`.

## Interceptor Errors

Interceptors reject missing or invalid identity headers before reaching the exception handler:

- customer endpoints (`/api/v1/orders/**`): `X-User-Id`
- store endpoints (`/api/v1/store/**`): `X-Store-Id`
- driver endpoints (`/api/v1/driver/**`): `X-Driver-Id`
- internal endpoints (`/api/v1/internal/**`): `X-Internal-Key`

Customer, store, and driver interceptors call `response.sendError(400, ...)` directly and bypass `GlobalExceptionHandler`. The internal interceptor returns `401` for a missing `X-Internal-Key` and `403` for an invalid key. `order-service.internal.secret` must be configured in every environment or the application fails startup.

## Design Rule

Do not expose Redis, database, Kafka, or serialization internals directly to clients. Convert internal failures into stable API error codes.
