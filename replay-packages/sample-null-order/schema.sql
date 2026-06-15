CREATE TABLE IF NOT EXISTS customer_order (
    id BIGINT PRIMARY KEY,
    external_order_id VARCHAR(100) NOT NULL,
    product_offering_id BIGINT,
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
