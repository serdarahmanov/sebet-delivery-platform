# Project Overview

## Purpose

Order-service is intended to own the order lifecycle after cart checkout confirmation.

It receives checkout events, creates orders, exposes customer and store order APIs, tracks active and scheduled orders in Redis, and provides the foundation for live delivery tracking and delivery verification.

## Current Implementation Stage

Customer, store, driver, and internal REST write/read endpoints, checkout
event ingestion, Redis hot-view read/write paths, the outbox/cache-eviction
pattern, and the scheduled-order-activation and proposal-timeout background
jobs are implemented. See `docs/13-implementation-gaps.md` for the
authoritative, per-area breakdown of what is implemented versus pending — this
section intentionally does not duplicate that list to avoid drifting out of
sync with it.

Notable remaining gaps as of this writing:

- Debezium connector deployment/runtime wiring for publishing outbox events (external to this service; see `docs/14-debezium-outbox.md`)
- delivery-arrival Kafka consumer
- WebSocket/STOMP broker configuration
- store response timeout background job
- deployment wiring (Dockerfile, compose, k8s/Helm, health probes)

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
