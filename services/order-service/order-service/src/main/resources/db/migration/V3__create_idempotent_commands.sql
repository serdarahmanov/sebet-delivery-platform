create table idempotent_commands (
    id uuid primary key,
    idempotency_key varchar(150) not null,
    action varchar(80) not null,
    order_id varchar(100) not null,
    request_hash varchar(64) not null,
    response_json jsonb not null,
    created_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now()
);

create unique index idx_idempotent_commands_action_key
    on idempotent_commands (action, idempotency_key);

create index idx_idempotent_commands_order_action
    on idempotent_commands (order_id, action, created_at desc);
