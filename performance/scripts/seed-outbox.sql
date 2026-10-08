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
WITH seeded_events AS MATERIALIZED (
    SELECT
        sequence,
        gen_random_uuid() AS event_id,
        gen_random_uuid() AS aggregate_id
    FROM generate_series(1, :event_count) AS sequence
)
SELECT
    event_id,
    now() + (sequence * interval '1 millisecond'),
    'ORDER',
    aggregate_id,
    'OrderCreatedEvent',
    'baekma.exchange',
    'order.created',
    json_build_object(
        'header', json_build_object(
            'messageId', event_id::text,
            'eventType', 'OrderCreatedEvent',
            'timestamp', now(),
            'version', 'v1'
        ),
        'payload', json_build_object('sequence', sequence)
    )::text,
    'PENDING',
    0
FROM seeded_events;
