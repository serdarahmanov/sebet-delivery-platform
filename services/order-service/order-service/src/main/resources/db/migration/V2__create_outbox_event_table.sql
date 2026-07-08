create table outbox_event (
    id uuid primary key,
    aggregate_type varchar(100) not null,
    aggregate_id varchar(100) not null,
    event_type varchar(150) not null,
    event_key varchar(150) not null,
    payload jsonb not null,
    headers jsonb,
    occurred_at timestamp with time zone not null,
    created_at timestamp with time zone not null default now()
);

create index idx_outbox_event_aggregate on outbox_event (aggregate_type, aggregate_id);
create index idx_outbox_event_event_type_created_at on outbox_event (event_type, created_at);
create index idx_outbox_event_created_at on outbox_event (created_at);
