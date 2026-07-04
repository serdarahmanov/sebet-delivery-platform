# API Design

## Base Path

```text
/api/cart
```

## Identity Header

Every endpoint expects:

```http
X-User-Id: <user-id>
```

The service trusts upstream infrastructure to authenticate the user and provide this header.

## Endpoint Groups

### Cart Read

```http
GET /api/cart
```

Returns a lightweight frontend-oriented `CartSummaryResponse`.

Behavior:

- Uses Redis response cache when available.
- Rebuilds from Redis cart on cache miss.
- Filters hard-invalid items from the response only.
- Hides item-level issue details.
- Hides promo issue details.
- Includes store-level issues.
- Includes only applied promo codes without promo issues.

### Item Mutations

```http
POST /api/cart/items
POST /api/cart/items/batch
PATCH /api/cart/items/{cartItemId}
DELETE /api/cart/items/{cartItemId}
```

These mutate Redis cart state using optimistic CAS writes.

### Basket Mutations

```http
PATCH /api/cart/store-baskets/{storeId}/address
PATCH /api/cart/store-baskets/{storeId}/delivery-method
DELETE /api/cart/store-baskets/{basketId}
```

Basket id format is `{cartId}:{storeId}`. Some endpoints use `storeId` because they operate directly on a store basket.

### Promo Code Endpoints

```http
POST /api/cart/store-baskets/{storeId}/promo-codes/claim
POST /api/cart/store-baskets/{storeId}/promo-codes/apply
DELETE /api/cart/store-baskets/{storeId}/promo-codes/{code}
```

Promo codes must be claimed before they can be selected.

### Checkout Endpoints

```http
POST /api/cart/store-baskets/{basketId}/checkout/initiate
POST /api/cart/store-baskets/{basketId}/checkout/confirm
```

Checkout endpoints validate product, inventory, store, delivery, and promotion state before returning ready/rejected responses.

## Response Conventions

- `GET /api/cart` uses lightweight response DTOs from `cart.dto.getcart`.
- Many mutation endpoints still return internal or verbose basket/cart DTOs.
- Validation and business errors generally use Spring `ProblemDetail` responses.
- CAS conflicts return HTTP 409.

## Status Code Conventions

- `200 OK`: successful reads and mutations returning a body.
- `204 No Content`: successful delete operations without a body.
- `400 Bad Request`: invalid request semantics.
- `404 Not Found`: cart, basket, or item not found.
- `409 Conflict`: concurrent cart modification detected.
- `422 Unprocessable Entity`: request is valid JSON but required cart state is missing.
- `503 Service Unavailable`: checkout or downstream processing could not complete.
