alter table processed_events
    add column status varchar(30) not null default 'COMPLETED',
    add column locked_by varchar(150),
    add column locked_until timestamp with time zone,
    add column completed_at timestamp with time zone;

update processed_events
set completed_at = processed_at
where completed_at is null;

create index idx_processed_events_status_locked_until
    on processed_events (status, locked_until);
