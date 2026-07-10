-- Replace the blanket unique constraint on order_id with a partial unique index
-- that only enforces uniqueness for ACTIVE proposals.
-- This allows a single order to have at most one active proposal at a time
-- while retaining historical (CANCELLED, TIMED_OUT, SYSTEM_CANCELLED, STORE_CANCELLED,
-- ACCEPTED, REJECTED) rows as an audit trail.
alter table order_proposals drop constraint uq_order_proposals_order_id;

create unique index uq_order_proposals_order_id_active
    on order_proposals (order_id)
    where status = 'ACTIVE';
