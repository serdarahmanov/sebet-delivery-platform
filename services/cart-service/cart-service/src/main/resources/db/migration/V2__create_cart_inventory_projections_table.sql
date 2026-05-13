CREATE TABLE cart_inventory_projections (
                                            id BIGSERIAL PRIMARY KEY,

                                            product_id VARCHAR(100) NOT NULL,
                                            store_id VARCHAR(100) NOT NULL,

                                            available_quantity NUMERIC(19, 3),
                                            stock_status VARCHAR(50) NOT NULL,
                                            available BOOLEAN NOT NULL,

                                            inventory_version BIGINT,
                                            inventory_updated_at TIMESTAMPTZ,

                                            projection_updated_at TIMESTAMPTZ NOT NULL,

                                            CONSTRAINT uk_cart_inventory_projection_product_store
                                                UNIQUE (product_id, store_id)
);

CREATE INDEX idx_cart_inventory_projection_product_id
    ON cart_inventory_projections (product_id);

CREATE INDEX idx_cart_inventory_projection_store_id
    ON cart_inventory_projections (store_id);