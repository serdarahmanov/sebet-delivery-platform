# Changelog

All notable changes to order-service should be documented in this file.

## Unreleased

- Added the first store order lifecycle write endpoints: accept, reject, and ready.
- Added shared order lifecycle transition handling, optimistic locking, Redis transition updates, and `ORDER_INVALID_TRANSITION` conflict responses.
- Added full `OUT_OF_STOCK` store rejection validation, null item guards, and persisted rejection metadata.
- Added store read endpoints for history, active orders, scheduled orders, detail, and status.
- Enforced per-order product uniqueness for order items.
- Corrected customer timeline semantics so only `READY_FOR_PICKUP` maps to `PACKED`.
- Reorganized project documentation into a short README, focused `docs/` files, and ADRs.
