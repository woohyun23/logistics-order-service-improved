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
    ('10000000-0000-0000-0000-' || lpad(sequence::text, 12, '0'))::uuid,
    now(),
    now(),
    ('40000000-0000-0000-0000-' || lpad(sequence::text, 12, '0'))::uuid,
    ('50000000-0000-0000-0000-' || lpad(sequence::text, 12, '0'))::uuid,
    ('60000000-0000-0000-0000-' || lpad(sequence::text, 12, '0'))::uuid,
    ('20000000-0000-0000-0000-' || lpad(sequence::text, 12, '0'))::uuid,
    1 + (sequence % 10),
    ('30000000-0000-0000-0000-' || lpad(sequence::text, 12, '0'))::uuid,
    'DELIVERING',
    '부하테스트 주문 ' || sequence,
    now() + interval '7 days'
FROM generate_series(1, 100) AS sequence;
