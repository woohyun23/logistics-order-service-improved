# 주문 서비스 부하테스트 환경

이 디렉터리는 Redis 캐시 fallback, 다중 Outbox Publisher, Consumer 멱등성 시나리오를 같은 조건에서 반복 측정하기 위한 공통 실행 환경입니다.

현재 이 브랜치에서는 실행 환경과 관측 지표만 제공합니다. 실제 성능 개선 수치는 각 후속 시나리오를 반복 실행한 뒤 별도로 기록합니다.

## 구성 요소

| 구성 요소 | 용도 | 로컬 주소 |
|---|---|---|
| Order Service | 테스트 대상 애플리케이션 | `http://localhost:19090` |
| PostgreSQL | 주문·Outbox·처리 이력 저장 | `localhost:15432` |
| RabbitMQ | 메시지 발행 및 Consumer 테스트 | AMQP `localhost:15672` |
| RabbitMQ Management | Queue 및 메시지 상태 확인 | `http://localhost:15673` |
| Redis | 외부 조회 fallback 캐시 | `localhost:16379` |
| WireMock | 상품·배송 외부 서비스 Mock | `http://localhost:18080` |
| Prometheus | Metric 수집 | `http://localhost:19091` |
| Grafana | Metric 시각화 | `http://localhost:13000` |

모든 계정과 비밀번호는 로컬 부하테스트 전용으로 `order/order`를 사용합니다. 운영 환경에서는 이 값을 사용하지 않습니다.

## 사전 조건

- Docker Desktop 또는 Docker Engine
- Docker Compose v2
- Java 17

## 실행

저장소 루트에서 다음 명령을 실행합니다.

```bash
docker compose -f performance/docker-compose.yml config
docker compose -f performance/docker-compose.yml up -d --build
docker compose -f performance/docker-compose.yml ps
```

Order Service 기동 여부를 확인합니다.

```bash
curl http://localhost:19090/actuator/health
curl http://localhost:19090/actuator/prometheus
```

Grafana 초기 계정은 로컬 전용 `admin/admin`이며 Prometheus Data Source는 자동으로 등록됩니다.

## k6 스모크 테스트

Docker 내부에서 실행할 때는 다음 명령을 사용합니다.

```bash
docker compose -f performance/docker-compose.yml --profile tools run --rm k6 run /scripts/smoke.js
```

로컬에 k6가 설치돼 있다면 다음 명령도 사용할 수 있습니다.

```bash
BASE_URL=http://localhost:19090 k6 run performance/k6/smoke.js
```

스모크 테스트는 Actuator Health와 Prometheus Metric 노출 여부만 확인합니다. 실제 부하 시나리오와 성능 목표는 후속 이슈에서 추가합니다.

## 캐시 Fallback 비교 테스트

외부 정보 조회의 캐시 fallback 적용 전후 비교는 다음 명령으로 실행합니다.

```bash
bash performance/scripts/run-cache-fallback.sh compare
```

공식 측정은 커밋된 깨끗한 Working Tree에서만 실행됩니다. 스크립트 동작만 빠르게 확인할 때는 다음 명령을 사용합니다.

```bash
ALLOW_DIRTY=true bash performance/scripts/run-cache-fallback.sh validate
```

상품 300초, 배송 30초의 실제 TTL 만료 동작은 실행 시간이 길어 별도로 측정합니다.

```bash
bash performance/scripts/run-cache-fallback.sh ttl
```

상세한 비교 기준, 장애 종류와 결과 해석 방법은 [`results/cache-fallback/README.md`](results/cache-fallback/README.md)를 참고합니다.

## 다중 Outbox Publisher 비교 테스트

과거의 잠금 없는 조회와 현재의 `FOR UPDATE SKIP LOCKED` 조회를 동일한 코드와 환경에서 비교합니다.

```bash
bash performance/scripts/run-outbox-publisher.sh compare
```

기본 조건은 10,000개 이벤트, Publisher 1·2·4개, 각 조합 3회 반복입니다. 빠른 실행 검증은 다음 명령을 사용합니다.

```bash
ALLOW_DIRTY=true bash performance/scripts/run-outbox-publisher.sh validate
```

RabbitMQ 전용 감사 큐의 전체 메시지 수와 고유 `messageId` 수를 비교해 중복과 유실을 계산합니다. 상세한 조건과 결과 해석 방법은 [`results/outbox-publisher/README.md`](results/outbox-publisher/README.md)를 참고합니다.

## Consumer 멱등성 부하테스트

동일한 `messageId`를 순차·동시·혼합 방식으로 재전달하고, 처리 Metric과 `p_processed_events` 및 주문 상태를 함께 비교합니다.

```bash
bash performance/scripts/run-consumer-idempotency.sh compare
```

기본 조건은 Consumer 동시성 4~16개이며 5개 시나리오를 각각 3회 실행합니다. 빠른 실행 검증은 다음 명령을 사용합니다.

```bash
ALLOW_DIRTY=true bash performance/scripts/run-consumer-idempotency.sh validate
```

실패 시나리오는 존재하지 않는 주문에 대한 처리를 실패시켜 이벤트 선점 이력이 함께 롤백되는지 확인하고, 주문을 생성한 뒤 동일한 이벤트를 재전달해 정상 처리 가능 여부를 검증합니다. 상세한 조건은 [`results/consumer-idempotency/README.md`](results/consumer-idempotency/README.md)를 참고합니다.

## 테스트 데이터 생성

애플리케이션이 기동해 JPA 테이블이 생성된 뒤 실행합니다.

```bash
docker compose -f performance/docker-compose.yml exec -T postgres \
  psql -U order -d logistics_order < performance/scripts/seed-orders.sql

docker compose -f performance/docker-compose.yml exec -T postgres \
  psql -v event_count=10000 -U order -d logistics_order < performance/scripts/seed-outbox.sql
```

기본 데이터 건수는 다음과 같습니다.

- 주문 100건
- `PENDING` Outbox 이벤트 10,000건

데이터를 초기화하려면 다음 명령을 실행합니다.

```bash
docker compose -f performance/docker-compose.yml exec -T postgres \
  psql -U order -d logistics_order < performance/scripts/reset.sql
```

## RabbitMQ 메시지 생성기

기본값은 `order.created` Routing Key로 1개의 고유 메시지를 발행합니다.

```bash
bash gradlew runPerformanceMessageGenerator
```

대량 메시지는 환경 변수로 설정합니다.

```bash
MESSAGE_COUNT=1000 \
MESSAGE_CONCURRENCY=10 \
MESSAGE_ID_MODE=unique \
bash gradlew runPerformanceMessageGenerator
```

고유 ID를 제한된 개수만 생성해 정상 이벤트와 중복 이벤트를 섞으려면 `cyclic` 모드를 사용합니다.

```bash
MESSAGE_COUNT=1000 \
MESSAGE_CONCURRENCY=100 \
MESSAGE_ID_MODE=cyclic \
UNIQUE_MESSAGE_COUNT=500 \
MESSAGE_ID_SEED=consumer-mixed \
bash gradlew runPerformanceMessageGenerator
```

동일한 `messageId`를 반복 전송하려면 다음과 같이 실행합니다.

```bash
MESSAGE_COUNT=1000 \
MESSAGE_CONCURRENCY=10 \
MESSAGE_ID_MODE=fixed \
FIXED_MESSAGE_ID=performance-duplicate-event \
MESSAGE_ROUTING_KEY=delivery.created \
ORDER_ID=<데이터베이스에 존재하는 주문-ID> \
DELIVERY_ID=<테스트용-배송-ID> \
MESSAGE_TEMPLATE_FILE=performance/message-generator/templates/delivery-created.json \
bash gradlew runPerformanceMessageGenerator
```

Consumer 멱등성 시나리오에서는 반드시 실제 DB에 존재하는 `ORDER_ID`를 사용해야 합니다.

지원하는 환경 변수는 다음과 같습니다.

| 환경 변수 | 기본값 |
|---|---|
| `RABBITMQ_HOST` | `localhost` |
| `RABBITMQ_PORT` | `15672` |
| `RABBITMQ_USERNAME` | `order` |
| `RABBITMQ_PASSWORD` | `order` |
| `MESSAGE_EXCHANGE` | `baekma.exchange` |
| `MESSAGE_ROUTING_KEY` | `order.created` |
| `MESSAGE_COUNT` | `1` |
| `MESSAGE_CONCURRENCY` | `1` |
| `MESSAGE_ID_MODE` | `unique` |
| `FIXED_MESSAGE_ID` | 실행 시 자동 생성 |
| `UNIQUE_MESSAGE_COUNT` | `MESSAGE_COUNT`와 동일 |
| `MESSAGE_ID_SEED` | `consumer-performance` |
| `MESSAGE_TEMPLATE_FILE` | 기본 메시지 템플릿 |
| `ORDER_ID` | 실행 시 임의 UUID 생성 |
| `DELIVERY_ID` | 실행 시 임의 UUID 생성 |

템플릿에서는 다음 값을 사용할 수 있습니다.

- `{{messageId}}`
- `{{sequence}}`
- `{{timestamp}}`
- `{{orderId}}`
- `{{deliveryId}}`

## 애플리케이션 Metric

Prometheus에서 다음 Metric을 조회할 수 있습니다.

| Metric | 태그 | 설명 |
|---|---|---|
| `order_external_query_total` | `target`, `source` | 상품·배송 조회의 `live`, `cache`, `failed` 결과 |
| `order_external_cache_lookup_total` | `target`, `result` | fallback 캐시의 `hit`, `miss` 결과 |
| `order_outbox_claimed_total` | 없음 | Publisher가 선점한 Outbox 이벤트 누적 수 |
| `order_outbox_claim_batch_size` | 없음 | 한 번에 선점한 Outbox 배치 크기 |
| `order_outbox_publish_total` | `result` | Outbox 메시지 발행 성공·실패 건수 |
| `order_consumer_event_total` | `result` | Consumer의 `processed`, `duplicate`, `failed` 건수 |
| `order_consumer_duration_seconds` | `quantile` | Consumer 전체 처리 시간의 p50, p95, p99 |
| `order_operation_duration_seconds` | `operation`, `result` | Outbox 배치와 외부 조회 처리 시간 |

`eventId`, `orderId`와 같은 고유 식별자는 Metric 태그에 포함하지 않습니다. 개별 이벤트 추적은 로그와 DB 데이터를 사용합니다.

## 반복 측정 기준

1. Before와 After에 동일한 Docker 자원 제한과 테스트 데이터를 사용합니다.
2. 예비 실행으로 JVM과 Connection Pool을 Warm-up합니다.
3. 본 측정을 최소 3회 실행합니다.
4. 평균뿐 아니라 p50, p95, p99를 기록합니다.
5. 대표값은 가장 좋은 결과가 아니라 반복 결과의 중앙값을 사용합니다.
6. Commit SHA, 실행 시각, 장비 사양, VU, 실행 시간과 데이터 건수를 함께 기록합니다.

원본 결과는 `performance/results/`에 저장하되 Git에는 커밋하지 않습니다. 요약 보고서는 각 결과 디렉터리의 `README.md`로 작성해 커밋할 수 있습니다.

## 종료

컨테이너를 종료합니다.

```bash
docker compose -f performance/docker-compose.yml down
```

데이터 볼륨까지 제거하려면 테스트 데이터가 더 이상 필요하지 않은지 확인한 뒤 다음 명령을 실행합니다.

```bash
docker compose -f performance/docker-compose.yml down -v
```
