# 다중 Outbox Publisher 부하테스트

## 측정 목적

여러 Order Service 인스턴스가 동시에 Outbox를 발행할 때 `FOR UPDATE SKIP LOCKED`가 처리량과 중복 발행에 미치는 영향을 측정합니다.

동일한 측정 커밋에서 조회 전략만 변경합니다.

| Variant | 조회 방식 |
|---|---|
| Before | `WHERE status='PENDING' ORDER BY created_at LIMIT 50` |
| After | 동일 조건에 `FOR UPDATE SKIP LOCKED` 적용 |

과거 기준 커밋은 `833d0e772e2c4a5e522da6a69a858b85273cbae2`, `SKIP LOCKED` 최초 적용 커밋은 `444034f`입니다.

## 측정 방법

- 10,000개의 `PENDING` 이벤트를 생성합니다.
- Publisher를 1개, 2개, 4개로 실행합니다.
- 현재 배치 크기 50개와 동일한 발행 주기를 Before/After에 적용합니다.
- 각 조합을 3회 실행하고 중앙값을 사용합니다.
- Outbox ID와 payload의 `header.messageId`를 동일하게 생성합니다.
- `order.created`에 바인딩한 전용 감사 큐에서 전체 메시지와 고유 `messageId`를 집계합니다.
- DB의 최초·최종 `published_at` 차이로 처리 시간을 계산합니다.
- 각 Publisher의 Prometheus 카운터로 인스턴스별 처리 건수를 확인합니다.
- PostgreSQL 연결·활성 연결·대기 Lock, Hikari 활성 연결, Process CPU와 JVM Heap 사용량을 1초 간격으로 수집합니다.

```bash
REPEATS=3 EVENT_COUNT=10000 INSTANCE_COUNTS="1 2 4" \
  bash performance/scripts/run-outbox-publisher.sh compare
```

원본 결과는 `raw/<실행 시각>/`에 저장되며 Git에는 커밋하지 않습니다.

## 중복 및 유실 계산

```text
중복 발행 수 = 전체 RabbitMQ 수신 메시지 수 - 고유 messageId 수
유실 수 = 초기 PENDING 이벤트 수 - 고유 messageId 수
```

중복 0건은 정상적인 Publisher 선점 경쟁에서 중복이 관찰되지 않았다는 뜻이며 RabbitMQ와 DB 사이의 물리적인 Exactly-once를 의미하지 않습니다.

## 최종 결과

공식 측정 후 `raw/<실행 시각>/summary.md`의 중앙값을 기록합니다.

| Variant | Publishers | 처리 시간 | events/s | 중복 | 유실 | Publisher별 처리 분포 |
|---|---:|---:|---:|---:|---:|---|
| Before | 1 | 측정 전 | 측정 전 | 측정 전 | 측정 전 | 측정 전 |
| Before | 2 | 측정 전 | 측정 전 | 측정 전 | 측정 전 | 측정 전 |
| Before | 4 | 측정 전 | 측정 전 | 측정 전 | 측정 전 | 측정 전 |
| After | 1 | 측정 전 | 측정 전 | 측정 전 | 측정 전 | 측정 전 |
| After | 2 | 측정 전 | 측정 전 | 측정 전 | 측정 전 | 측정 전 |
| After | 4 | 측정 전 | 측정 전 | 측정 전 | 측정 전 | 측정 전 |

## 해석 시 주의사항

- 로컬 Docker의 동일 조건 비교이며 운영 환경의 최대 처리량을 의미하지 않습니다.
- Publisher 수를 늘리면 RabbitMQ와 PostgreSQL이 병목이 되어 처리량이 선형으로 증가하지 않을 수 있습니다.
- 잠금 없는 Before는 중복 메시지를 더 빨리 발행할 수 있으므로 단순 RabbitMQ 수신량을 유효 처리량으로 보지 않습니다.
- 유효 처리량은 초기 고유 이벤트 수를 처리 완료 시간으로 나눠 계산합니다.
- RabbitMQ 발행 성공 후 DB 커밋 전 장애로 발생하는 재발행은 이 정상 경쟁 테스트의 범위가 아닙니다.
