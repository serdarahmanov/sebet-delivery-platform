alter table idempotent_commands
    add column status varchar(20) not null default 'COMPLETED',
    add column locked_by varchar(120),
    add column locked_until timestamp with time zone,
    add column completed_at timestamp with time zone;

update idempotent_commands
set completed_at = updated_at
where completed_at is null;

alter table idempotent_commands
    alter column response_json drop not null;

create index idx_idempotent_commands_status_locked_until
    on idempotent_commands (status, locked_until);
