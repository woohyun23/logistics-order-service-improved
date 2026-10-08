INSERT INTO p_outbox_events (
    id,
    created_at,
    aggregate_type,
    aggregate_id,
    event_type,
    exchange,
    routing_key,
    payload,
    status,
    retry_count
)
SELECT
    gen_random_uuid(),
    now() + (sequence * interval '1 millisecond'),
    'ORDER',
    gen_random_uuid(),
    'OrderCreatedEvent',
    'baekma.exchange',
    'order.created',
    json_build_object(
        'header', json_build_object(
            'messageId', gen_random_uuid()::text,
            'eventType', 'OrderCreatedEvent',
            'timestamp', now(),
            'version', 'v1'
        ),
        'payload', json_build_object('sequence', sequence)
    )::text,
    'PENDING',
    0
FROM generate_series(1, 10000) AS sequence;
