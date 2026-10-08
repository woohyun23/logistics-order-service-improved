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
SELECT
    gen_random_uuid(),
    now(),
    now(),
    gen_random_uuid(),
    gen_random_uuid(),
    gen_random_uuid(),
    gen_random_uuid(),
    1 + (sequence % 10),
    gen_random_uuid(),
    'DELIVERING',
    '부하테스트 주문 ' || sequence,
    now() + interval '7 days'
FROM generate_series(1, 100) AS sequence;
