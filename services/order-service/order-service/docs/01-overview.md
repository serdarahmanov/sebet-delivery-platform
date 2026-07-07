# Project Overview

## Purpose

Order-service is intended to own the order lifecycle after cart checkout confirmation.

It receives checkout events, creates orders, exposes customer and store order APIs, tracks active and scheduled orders in Redis, and provides the foundation for live delivery tracking and delivery verification.

## Current Implementation Stage

Implemented:

- customer and store REST controllers
- request and response DTOs
- order lifecycle enums
- Redis DTOs
- Redis key registry
- Redis repository classes
- MVC interceptors for `X-User-Id`, `X-Store-Id`, `X-Driver-Id`, and `X-Internal-Key`
- PostgreSQL order, item, and status-history entities
- Spring Data JPA repositories
- Flyway migration for durable order tables
- internal order creation service
- checkout confirmed event DTOs
- checkout event to order creation command mapper
- checkout event Kafka consumer
- checkout event retry and dead-letter handling
- Redis lock for checkout order creation
- Redis hot-view writes during checkout order creation
- customer read service methods
- first store lifecycle write endpoints: accept, reject, and ready
- repository, order creation, and Kafka listener integration tests
- global exception handler for controller-handled exceptions
- input validation for amount fields and delivery address JSON

Pending:

- customer write service methods
- store read service methods
- remaining store write service methods
- driver and internal service methods
- order event producers
- delivery-arrival Kafka consumer
- WebSocket/STOMP broker configuration
- background jobs

## Users

- Customers viewing order history, active orders, tracking, cancellation receipts, and proposed changes.
- Store staff managing active orders, scheduled orders, acceptance, rejection, readiness, cancellation, and item-change proposals.
- Delivery/driver systems that update tracking and arrival state.
- Backend services such as cart-service that emit checkout confirmation events.

## Main Capabilities

- Create an order from a cart checkout event.
- Maintain active order sets for users and stores.
- Maintain scheduled order queues for stores.
- Expose customer order history and active order views.
- Expose store order dashboard and action endpoints.
- Manage order status transitions.
- Support proposed item changes when stock issues appear after store acceptance.
- Support delivery tracking and verification code flows.

## Out of Scope

- Cart ownership.
- Payment authorization.
- Product catalog ownership.
- Inventory ownership.
- Delivery assignment algorithms.
- Refund execution.
