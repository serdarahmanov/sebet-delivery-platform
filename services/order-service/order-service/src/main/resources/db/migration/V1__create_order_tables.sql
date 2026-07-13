create table orders (
    id uuid primary key,
    version bigint not null,
    customer_id varchar(64) not null,
    store_id varchar(64) not null,
    cart_id varchar(64) not null,
    status varchar(40) not null,
    schedule_type varchar(20) not null,
    scheduled_for timestamp with time zone,
    subtotal_amount numeric(12, 2) not null,
    item_discount_amount numeric(12, 2) not null,
    order_discount_amount numeric(12, 2) not null,
    delivery_fee_amount numeric(12, 2) not null,
    total_amount numeric(12, 2) not null,
    currency varchar(3) not null,
    delivery_address_json jsonb not null,
    delivery_lat numeric(9, 6) not null,
    delivery_lng numeric(9, 6) not null,
    store_lat numeric(9, 6),
    store_lng numeric(9, 6),
    driver_id varchar(64),
    driver_assigned_at timestamp with time zone,
    cancellation_reason varchar(80),
    cancelled_by varchar(30),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    delivered_at timestamp with time zone,
    cancelled_at timestamp with time zone,
    delivery_phone_number varchar(30),
    delivered_proof_image_url text,
    constraint chk_orders_amounts_non_negative check (
        subtotal_amount >= 0
        and item_discount_amount >= 0
        and order_discount_amount >= 0
        and delivery_fee_amount >= 0
        and total_amount >= 0
    ),
    constraint chk_orders_currency_uppercase check (currency = upper(currency))
);

create unique index idx_orders_cart_id on orders (cart_id);
create index idx_orders_customer_created_at on orders (customer_id, created_at desc);
create index idx_orders_store_created_at on orders (store_id, created_at desc);
create index idx_orders_scheduled_for on orders (scheduled_for) where scheduled_for is not null;

create table order_items (
    id uuid primary key,
    order_id uuid not null,
    line_number integer not null,
    product_id varchar(64) not null,
    product_name varchar(255) not null,
    quantity numeric(12, 3) not null,
    unit varchar(20) not null,
    unit_price_amount numeric(12, 2) not null,
    gross_amount numeric(12, 2) not null,
    discount_amount numeric(12, 2) not null,
    net_amount numeric(12, 2) not null,
    image_url text,
    created_at timestamp with time zone not null,
    constraint fk_order_items_order foreign key (order_id) references orders (id) on delete cascade,
    constraint chk_order_items_amounts_non_negative check (
        quantity > 0
        and unit_price_amount >= 0
        and gross_amount >= 0
        and discount_amount >= 0
        and net_amount >= 0
    )
);

create unique index idx_order_items_order_line_number on order_items (order_id, line_number);
create unique index idx_order_items_order_product on order_items (order_id, product_id);
create index idx_order_items_product on order_items (product_id);

create table order_status_history (
    id uuid primary key,
    order_id uuid not null,
    from_status varchar(40),
    to_status varchar(40) not null,
    changed_by_type varchar(30) not null,
    changed_by_id varchar(64),
    reason varchar(120),
    metadata_json jsonb,
    created_at timestamp with time zone not null,
    constraint fk_order_status_history_order foreign key (order_id) references orders (id) on delete cascade
);

create index idx_order_status_history_order_created_at on order_status_history (order_id, created_at asc);
create index idx_order_status_history_to_status_created_at on order_status_history (to_status, created_at desc);

update orders
set schedule_type = 'ASAP'
where schedule_type = 'IMMEDIATE';

alter table orders
    alter column delivery_lat drop not null,
    alter column delivery_lng drop not null;

create table processed_events (
    event_id uuid primary key,
    event_type varchar(100) not null,
    processed_at timestamp with time zone not null default now(),
    occurred_at timestamp with time zone
);

create index idx_processed_events_event_type_processed_at
    on processed_events (event_type, processed_at);

-- orders: promo codes applied at checkout
alter table orders
    add column selected_promo_codes jsonb not null default '[]'::jsonb;

-- orders: fee quote that was locked at checkout (nullable - legacy orders predate this column)
alter table orders
    add column fee_quote_id varchar(100);

-- orders: service and small-order fee amounts (always present from this migration forward)
alter table orders
    add column service_fee_amount numeric(12, 2) not null default 0,
    add column small_order_fee_amount numeric(12, 2) not null default 0;

-- orders: store coordinates - backfill any legacy nulls then enforce not null
update orders set store_lat = 0, store_lng = 0 where store_lat is null or store_lng is null;
alter table orders
    alter column store_lat set not null,
    alter column store_lng set not null;

-- order_items: merchant SKU from product catalog (nullable - not all products have a SKU)
alter table order_items
    add column sku varchar(150);
