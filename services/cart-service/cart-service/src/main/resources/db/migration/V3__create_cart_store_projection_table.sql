CREATE TABLE cart_store_projection (
                                       store_id VARCHAR(100) PRIMARY KEY,

                                       store_name VARCHAR(255) NOT NULL,
                                       store_logo_url TEXT,

                                       active BOOLEAN NOT NULL,
                                       open BOOLEAN NOT NULL,
                                       accepting_orders BOOLEAN NOT NULL,

                                       minimum_order_amount NUMERIC(19, 2),
                                       free_delivery_threshold NUMERIC(19, 2),
                                       base_delivery_fee NUMERIC(19, 2),

                                       estimated_preparation_minutes INTEGER,

                                       store_version BIGINT NOT NULL,
                                       store_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

                                       created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                                       updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_cart_store_projection_active
    ON cart_store_projection (active);

CREATE INDEX idx_cart_store_projection_open
    ON cart_store_projection (open);

CREATE INDEX idx_cart_store_projection_accepting_orders
    ON cart_store_projection (accepting_orders);

CREATE INDEX idx_cart_store_projection_store_version
    ON cart_store_projection (store_version);