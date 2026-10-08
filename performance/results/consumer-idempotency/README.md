# Consumer 멱등성 부하테스트

## 측정 목적

동일한 `EventHeader.messageId`를 가진 Saga 이벤트가 순차 또는 동시에 재전달되더라도 PostgreSQL의 UNIQUE 제약과 `INSERT ... ON CONFLICT DO NOTHING` 선점을 통해 비즈니스 처리가 한 번만 수행되는지 검증합니다.

물리적인 Exactly-once delivery가 아니라 At-least-once 전달 환경에서 최종 비즈니스 결과를 한 번만 반영하는 Effectively-once를 검증합니다.

## 실행 방법

공식 측정은 커밋된 Working Tree에서 각 시나리오를 3회 실행합니다.

```bash
bash performance/scripts/run-consumer-idempotency.sh compare
```

스크립트와 환경을 빠르게 검증할 때는 각 시나리오를 1회 실행합니다.

```bash
ALLOW_DIRTY=true bash performance/scripts/run-consumer-idempotency.sh validate
```

## 테스트 시나리오

| 시나리오 | 전달 조건 | 기대 결과 |
|---|---|---|
| `sequential-100` | 같은 ID 100건, Producer 동시성 1 | 처리 1, 중복 99 |
| `concurrent-100` | 같은 ID 100건, Producer 동시성 100 | 처리 1, 중복 99 |
| `concurrent-1000` | 같은 ID 1,000건, Producer 동시성 100 | 처리 1, 중복 999 |
| `mixed-1000` | 고유 ID 500개를 각각 2회, 동시성 100 | 처리 500, 중복 500 |
| `failure-retry` | 존재하지 않는 주문으로 실패 후 같은 ID 재전달 | 실패 시 이력 0, 재전달 후 처리 1 |

## 검증 불변 조건

```text
처리 Metric = p_processed_events 저장 건수
전체 수신 = 처리 + 중복 차단 + 실패
동일 이벤트 N건 전달 = 처리 1 + 중복 차단 N-1
실패 직후 처리 이력 = 0
재전달 후 처리 이력 = 1
```

RabbitMQ Queue Ready·Unacked, Hikari 활성 연결, DB 연결, 완료 시간과 Consumer p50·p95·p99도 함께 기록합니다. 원본 결과는 `raw/<실행 시각>/`에 보관되며 Git에는 커밋하지 않습니다.

`order_consumer_event_total{result="duplicate"}`은 `ON CONFLICT DO NOTHING`의 반환값이 0이어서 비즈니스 로직을 실행하지 않은 횟수입니다. 따라서 이 값과 `p_processed_events` 저장 건수를 함께 확인해 애플리케이션 조회뿐 아니라 DB UNIQUE 제약을 최종 방어선으로 사용했는지 검증합니다.

## 최종 결과

공식 측정 후 각 시나리오 3회 결과의 중앙값과 실행 조건을 기록합니다.
