INSERT INTO customer_order(
    id,
    external_order_id,
    product_offering_id,
    status
)
VALUES (
    1,
    'DEMO-ORDER-1',
    NULL,
    'IN_PROGRESS'
)
ON CONFLICT (id) DO NOTHING;
