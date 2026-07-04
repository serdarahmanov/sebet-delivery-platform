# Error Handling

## General Style

The service primarily uses Spring exceptions:

- `ResponseStatusException`
- validation exceptions from `jakarta.validation`
- custom `CartVersionConflictException`

Spring Boot returns `ProblemDetail` style error bodies.

## Optimistic Lock Conflicts

`CartVersionConflictException` maps to:

```http
409 Conflict
```

This happens when Redis CAS detects that another request modified the cart between read and save.

`GlobalExceptionHandler` records a CAS conflict metric before returning the response.

## Common Error Cases

- Missing cart: `404 Not Found`
- Missing basket: `404 Not Found`
- Empty basket: `404 Not Found`
- Missing cart item: `404 Not Found`
- Unclaimed promo code selection: `400 Bad Request`
- Missing address before delivery scheduling: `422 Unprocessable Entity`
- Checkout executor saturated: `503 Service Unavailable`
- Checkout timeout: `503 Service Unavailable`
- Kafka publish failure after checkout CAS save: `503 Service Unavailable`

## Downstream Failures

Promotion-service failures become degraded promotion evaluation responses.

Delivery-service failures become unavailable or empty quote responses. Validation converts missing delivery availability or fee into user-visible delivery issues.

## Kafka Failures

Projection event processing failures are retried by Spring Kafka. After retries are exhausted, records are sent to DLT.

Malformed JSON is sent directly to DLT without retries.

If DLT publish itself fails, the message is logged and a metric is recorded because the message may be permanently lost.

## API Error Design Rule

Use stable status codes and clear messages. Avoid leaking downstream stack traces or internal serialization details to clients.
