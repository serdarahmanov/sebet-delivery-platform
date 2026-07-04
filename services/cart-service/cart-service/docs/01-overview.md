# Project Overview

## Purpose

Cart-service manages shopping carts for the Sebet delivery platform. It is responsible for the cart experience before an order is created.

The service keeps cart state fast and user-specific in Redis, enriches that state with product/inventory/store projections from PostgreSQL, validates delivery and promotion rules, and emits checkout confirmation events for downstream order processing.

## Users

- Customers using the Sebet app or web frontend.
- Frontend clients rendering cart, basket, promotion, and checkout screens.
- Backend services that publish product, inventory, and store events.
- Order-service or another downstream service that consumes checkout confirmation events.

## Main Capabilities

- Create and update user carts.
- Group items into store baskets.
- Add, update, batch upsert, and remove cart items.
- Set basket delivery address and delivery method.
- Claim, select, and remove promo codes.
- Validate cart state against product, inventory, store, delivery, and promotion rules.
- Build a lightweight `GET /api/cart` response for frontend rendering.
- Initiate checkout with quote and validation checks.
- Confirm checkout and publish a checkout event.

## In Scope

- Cart state ownership.
- Basket-level checkout preparation.
- Projection-based validation.
- Delivery quote state stored with the basket.
- Promotion evaluation and degraded promotion behavior.
- Kafka-driven projection updates.
- Checkout confirmation event publishing.

## Out of Scope

- User authentication and JWT parsing.
- Product catalog ownership.
- Inventory ownership.
- Store ownership.
- Promotion ownership.
- Delivery pricing ownership.
- Payment processing.
- Order persistence.

## Important Runtime Assumption

The authenticated user id is supplied by upstream infrastructure in the `X-User-Id` request header.
