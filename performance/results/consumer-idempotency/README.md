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

2026년 10월 8일 커밋 `3f1230fe7b87447d73d22a7503a59ef69286f80d`의 깨끗한 Working Tree에서 각 시나리오를 3회 실행했습니다.

- Consumer 동시성: 최소 4, 최대 16
- Order Service 컨테이너: CPU 1개, Memory 768MB
- Docker Engine: 27.4.0
- Docker Compose: 2.31.0
- 결과값: 3회 실행 중앙값

| 시나리오 | 수신 | 처리 | 중복 차단 | 실패 | 처리 이력 | 주문 변경 | 완료(ms) | p50(ms) | p95(ms) | p99(ms) | 최대 Unacked | 최대 Hikari | 불변 조건 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 순차 중복 100건 | 100 | 1 | 99 | 0 | 1 | 1 | 2,273 | 1.409 | 83.853 | 285.180 | 0 | 4 | 충족 |
| 동시 중복 100건 | 100 | 1 | 99 | 0 | 1 | 1 | 2,536 | 1.475 | 83.853 | 285.180 | 0 | 4 | 충족 |
| 동시 중복 1,000건 | 1,000 | 1 | 999 | 0 | 1 | 1 | 8,115 | 0.999 | 83.870 | 88.064 | 189 | 4 | 충족 |
| 고유·중복 혼합 1,000건 | 1,000 | 500 | 500 | 0 | 500 | 1 | 7,209 | 2.351 | 88.072 | 92.266 | 555 | 4 | 충족 |
| 최초 실패 후 재전달 | 2 | 1 | 0 | 1 | 1 | 1 | 2,294 | 41.943 | 73.400 | 73.400 | 0 | 0 | 충족 |

## 결과 해석

- 동일 이벤트 1,000건을 Producer 동시성 100으로 전달해도 `ON CONFLICT DO NOTHING`으로 999건을 차단하고 비즈니스 처리와 처리 이력을 각각 1건으로 제한했습니다.
- 고유 ID 500개를 각각 2회씩 동시에 전달한 혼합 시나리오에서는 500건을 처리하고 500건을 중복으로 차단했으며, `p_processed_events`도 정확히 500건 저장됐습니다.
- 처리에 실패한 직후에는 주문 데이터와 처리 이력이 모두 0건이었고, 같은 이벤트 ID를 재전달하면 주문 변경과 처리 이력이 각각 1건 저장됐습니다. 이 결과는 3회 모두 동일했습니다.
- 동시 중복 1,000건의 Consumer p95는 83.870ms, 혼합 1,000건의 p95는 88.072ms였습니다. 이 값은 Micrometer percentile로 측정한 Consumer 내부 처리 시간입니다.
- 시나리오 완료 시간에는 Gradle JavaExec 기반 Producer 기동 시간이 포함되므로 Consumer 순수 처리량으로 해석하지 않습니다.
- Queue Ready는 관측되지 않았지만 동시 중복 1,000건에서 Unacked 189건, 혼합 1,000건에서 555건의 중앙값이 관측되어 Consumer 동시 처리 중 메시지 적체가 발생했음을 확인했습니다.

## 보장 범위

- 이 측정은 RabbitMQ의 물리적인 Exactly-once delivery를 보장하지 않습니다.
- PostgreSQL 로컬 트랜잭션 안에서 이벤트 ID 선점과 주문 변경을 함께 처리하여, 중복 전달이 비즈니스 결과에 한 번만 반영되는 Effectively-once 결과를 검증합니다.
- 처리 이력 DB를 사용할 수 없는 상황, Consumer 프로세스 강제 종료, 네트워크 단절과 같은 장애 주입은 이번 측정 범위에 포함하지 않습니다.
