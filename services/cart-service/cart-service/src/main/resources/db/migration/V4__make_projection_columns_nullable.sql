ALTER TABLE cart_product_projections
    ALTER COLUMN unit_price DROP NOT NULL;

ALTER TABLE cart_inventory_projections
    ALTER COLUMN stock_status DROP NOT NULL;

ALTER TABLE cart_store_projection
    ALTER COLUMN store_version DROP NOT NULL;
