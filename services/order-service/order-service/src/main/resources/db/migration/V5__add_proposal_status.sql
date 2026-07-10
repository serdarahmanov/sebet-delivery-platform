alter table order_proposals
    add column status varchar(32) not null default 'ACTIVE';
