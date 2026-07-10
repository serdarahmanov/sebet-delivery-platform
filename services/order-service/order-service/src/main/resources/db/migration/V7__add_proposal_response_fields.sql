alter table order_proposals
    add column global_decision    varchar(40),
    add column item_decisions_json jsonb,
    add column responded_at       timestamp with time zone,
    add column applied_at         timestamp with time zone;
