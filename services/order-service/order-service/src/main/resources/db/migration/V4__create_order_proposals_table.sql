create table order_proposals (
    id uuid primary key,
    order_id uuid not null,
    store_id varchar(64) not null,
    proposed_at timestamp with time zone not null,
    items_json jsonb not null,
    created_at timestamp with time zone not null default now(),
    constraint fk_order_proposals_order foreign key (order_id) references orders(id) on delete cascade,
    constraint uq_order_proposals_order_id unique (order_id)
);
