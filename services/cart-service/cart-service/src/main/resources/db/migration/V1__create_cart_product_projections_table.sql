CREATE TABLE cart_product_projections (
                                          id BIGSERIAL PRIMARY KEY,

                                          product_id VARCHAR(100) NOT NULL,
                                          store_id VARCHAR(100) NOT NULL,

                                          sku VARCHAR(100),

                                          name VARCHAR(255) NOT NULL,
                                          brand_name VARCHAR(255),

                                          category_id VARCHAR(100) NOT NULL,
                                          category_name VARCHAR(255),

                                          image_url VARCHAR(1000),

                                          unit VARCHAR(50) NOT NULL,

                                          min_quantity NUMERIC(19, 3) NOT NULL,
                                          max_quantity NUMERIC(19, 3),
                                          quantity_step NUMERIC(19, 3) NOT NULL,

                                          unit_price NUMERIC(19, 2) NOT NULL,
                                          original_unit_price NUMERIC(19, 2),

                                          active BOOLEAN NOT NULL,
                                          sellable BOOLEAN NOT NULL,

                                          product_version BIGINT,
                                          price_version BIGINT,

                                          product_updated_at TIMESTAMPTZ,
                                          price_updated_at TIMESTAMPTZ,

                                          projection_updated_at TIMESTAMPTZ NOT NULL,

                                          CONSTRAINT uk_cart_product_projection_product_store
                                              UNIQUE (product_id, store_id)
);

CREATE INDEX idx_cart_product_projection_product_id
    ON cart_product_projections (product_id);

CREATE INDEX idx_cart_product_projection_store_id
    ON cart_product_projections (store_id);