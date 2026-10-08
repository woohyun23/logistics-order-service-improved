# 다중 Outbox Publisher 부하테스트

## 측정 목적

여러 Order Service 인스턴스가 동시에 Outbox를 발행할 때 `FOR UPDATE SKIP LOCKED`가 처리량과 중복 발행에 미치는 영향을 측정합니다.

동일한 측정 커밋에서 조회 전략만 변경합니다.

| Variant | 조회 방식 |
|---|---|
| Before | `WHERE status='PENDING' ORDER BY created_at LIMIT 50` |
| After | 동일 조건에 `FOR UPDATE SKIP LOCKED` 적용 |

과거 기준 커밋은 `833d0e772e2c4a5e522da6a69a858b85273cbae2`, `SKIP LOCKED` 최초 적용 커밋은 `444034f`입니다.

공식 측정 커밋은 `c756a2ec5a33b01575535e362f991b1eccf5adf2`이며, 변경 사항이 없는 Working Tree에서 실행했습니다.

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

2026년 10월 8일 동일한 로컬 Docker 환경에서 각 조합을 3회 실행한 중앙값입니다. Docker Engine은 27.4.0, Docker Compose는 2.31.0이며 각 Publisher 컨테이너는 CPU 1개와 Memory 768MB로 제한했습니다.

| Variant | Publishers | 처리 시간(ms) | events/s | 전체 수신 | 고유 | 중복 | 유실 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Before | 1 | 7,888 | 1,267.75 | 10,000 | 10,000 | 0 | 0 |
| Before | 2 | 7,122 | 1,404.10 | 17,200 | 10,000 | 7,200 | 0 |
| Before | 4 | 5,913 | 1,691.19 | 16,250 | 10,000 | 6,250 | 0 |
| After | 1 | 7,912 | 1,263.90 | 10,000 | 10,000 | 0 | 0 |
| After | 2 | 4,347 | 2,300.44 | 10,000 | 10,000 | 0 | 0 |
| After | 4 | 5,581 | 1,791.79 | 10,000 | 10,000 | 0 | 0 |

### 자원 사용량 중앙값

각 값은 실행 중 관측한 최댓값을 구한 뒤 3회 결과의 중앙값을 사용했습니다. Process CPU와 JVM Heap은 여러 Publisher 중 가장 높은 인스턴스 값입니다.

| Variant | Publishers | 최대 DB 연결 | 최대 DB 활성 | 최대 Lock 대기 | 최대 Hikari 활성 | 최대 Process CPU | 최대 JVM Heap |
|---|---:|---:|---:|---:|---:|---:|---:|
| Before | 1 | 7 | 1 | 0 | 1 | 98.49% | 85.53 MiB |
| Before | 2 | 13 | 2 | 0 | 1 | 99.60% | 87.82 MiB |
| Before | 4 | 22 | 1 | 0 | 1 | 99.71% | 89.72 MiB |
| After | 1 | 7 | 2 | 0 | 1 | 99.06% | 83.65 MiB |
| After | 2 | 12 | 1 | 0 | 1 | 99.70% | 83.60 MiB |
| After | 4 | 22 | 1 | 0 | 1 | 99.72% | 90.60 MiB |

### Publisher별 처리 분포

각 항목은 한 번의 실행에서 각 Publisher가 발행한 메시지 수입니다.

| Variant | Publishers | 1회차 | 2회차 | 3회차 |
|---|---:|---|---|---|
| Before | 1 | 10,000 | 10,000 | 10,000 |
| Before | 2 | 8,750 / 9,150 | 8,600 / 8,450 | 8,600 / 8,600 |
| Before | 4 | 6,950 / 2,150 / 3,150 / 3,400 | 4,550 / 1,500 / 1,700 / 8,500 | 4,100 / 5,550 / 3,850 / 4,500 |
| After | 1 | 10,000 | 10,000 | 10,000 |
| After | 2 | 5,100 / 4,900 | 5,000 / 5,000 | 4,950 / 5,050 |
| After | 4 | 1,150 / 4,350 / 3,450 / 1,050 | 6,900 / 2,400 / 400 / 300 | 800 / 1,300 / 1,200 / 6,700 |

## 결과 해석

- Publisher 2개 기준으로 `SKIP LOCKED` 적용 후 유효 처리량은 1,404.10에서 2,300.44 events/s로 약 63.8% 증가했고, 중복 발행 중앙값은 7,200건에서 0건으로 감소했습니다.
- After에서 Publisher를 1개에서 2개로 늘리자 유효 처리량이 약 82.0% 증가했습니다.
- Publisher 4개는 1개보다 처리량이 약 41.8% 높았지만, 2개보다 약 22.1% 낮았습니다. 인스턴스 최고 Process CPU가 약 100%에 도달했고 실행별 처리 분포도 한 인스턴스에 치우쳐, 현재 로컬 자원과 25ms 스케줄 주기에서는 Publisher 2개가 가장 효율적이었습니다.
- After의 모든 실행에서 고유 이벤트 10,000건이 모두 수신됐고 중복과 유실은 관찰되지 않았습니다.
- Lock 대기 표본이 0인 것은 `SKIP LOCKED`가 잠긴 행을 기다리지 않고 건너뛴 결과와 부합하지만, 1초 표본 간격 사이의 순간적인 대기를 완전히 배제한다는 의미는 아닙니다.

## 해석 시 주의사항

- 로컬 Docker의 동일 조건 비교이며 운영 환경의 최대 처리량을 의미하지 않습니다.
- Publisher 수를 늘리면 RabbitMQ와 PostgreSQL이 병목이 되어 처리량이 선형으로 증가하지 않을 수 있습니다.
- 잠금 없는 Before는 중복 메시지를 더 빨리 발행할 수 있으므로 단순 RabbitMQ 수신량을 유효 처리량으로 보지 않습니다.
- 유효 처리량은 초기 고유 이벤트 수를 처리 완료 시간으로 나눠 계산합니다.
- RabbitMQ 발행 성공 후 DB 커밋 전 장애로 발생하는 재발행은 이 정상 경쟁 테스트의 범위가 아닙니다.
