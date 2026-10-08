INSERT INTO p_orders (
    id,
    created_at,
    updated_at,
    orderer_user_id,
    departure_hub_id,
    receiver_company_id,
    product_id,
    quantity,
    delivery_id,
    status,
    request_message,
    due_date
)
VALUES (
    :'order_id'::uuid,
    now(),
    now(),
    '40000000-0000-0000-0000-000000000001'::uuid,
    '50000000-0000-0000-0000-000000000001'::uuid,
    '60000000-0000-0000-0000-000000000001'::uuid,
    '20000000-0000-0000-0000-000000000001'::uuid,
    1,
    NULL,
    'PENDING',
    'Consumer 멱등성 부하테스트 주문',
    now() + interval '7 days'
)
ON CONFLICT (id) DO NOTHING;
