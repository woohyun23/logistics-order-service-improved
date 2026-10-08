# Redis 캐시 Fallback 부하테스트

## 측정 목적

상품·배송 서비스 장애 시 Redis 캐시 fallback이 주문 외부 정보 조회의 성공률과 응답 시간에 미치는 영향을 측정합니다.

대상 API는 `GET /api/v1/orders/{orderId}/external-info`입니다.

## 비교 기준

역사적인 fallback 적용 전 커밋 `119d208bede6aef561d5b9a0b99c6284a825e845`에는 대상 API가 존재하지 않습니다. 따라서 이 커밋과 현재 API를 직접 비교하지 않습니다.

동일한 측정 커밋에서 다음 설정만 변경하여 fallback의 기여분을 분리합니다.

| Variant | 설정 | 동작 |
|---|---|---|
| Before | `CACHE_FALLBACK_ENABLED=false` | 외부 조회 실패 시 캐시를 조회하지 않고 기존 예외 반환 |
| After | `CACHE_FALLBACK_ENABLED=true` | 외부 조회 실패 시 유효한 최근 캐시가 있으면 반환 |

fallback 기능을 최초 구현한 커밋은 `4375ca558f6ff5e3a8e501def8e580542ad71f75`입니다. 실제 측정에 사용한 공통 코드의 SHA는 실행 시 `raw/manifest.txt`에 기록됩니다.

## 장애 주입 상태

WireMock Scenario State를 사용해 상품과 배송 Mock을 독립적으로 변경합니다.

| State | 동작 |
|---|---|
| `Started` | 정상 응답 |
| `SERVER_ERROR` | HTTP 500 반환 |
| `TIMEOUT` | Feign read timeout보다 긴 4초 지연 |
| `CONNECTION_FAILURE` | 연결을 강제로 reset |
| `RECOVERED` | 복구 후 변경된 최신 상품·배송 정보 반환 |

## 테스트 데이터

`seed-orders.sql`은 100개의 주문·상품·배송 ID를 결정적인 UUID로 생성합니다. k6는 요청을 여러 주문에 분산하여 특정 캐시 키 하나에만 부하가 집중되지 않도록 합니다.

## 실행

### 빠른 검증

```bash
ALLOW_DIRTY=true bash performance/scripts/run-cache-fallback.sh validate
```

### Before/After 공식 비교

```bash
REPEATS=3 VUS=10 DURATION=20s \
  bash performance/scripts/run-cache-fallback.sh compare
```

각 반복마다 애플리케이션을 재시작해 Circuit Breaker 상태를 초기화하고 Redis를 비운 뒤 필요한 캐시를 다시 예열합니다.

실행되는 시나리오는 다음과 같습니다.

1. 정상 외부 조회
2. 상품 서비스 HTTP 500
3. 배송 서비스 HTTP 500
4. 상품·배송 서비스 동시 HTTP 500
5. 상품·배송 서비스 동시 timeout
6. 상품·배송 서비스 동시 connection reset
7. 캐시 미존재 상태의 동시 장애
8. 외부 서비스 복구와 최신 데이터 갱신

### TTL 만료

```bash
bash performance/scripts/run-cache-fallback.sh ttl
```

기본 설정 그대로 배송 캐시는 31초, 상품 캐시는 301초 대기한 뒤 장애를 발생시켜 기존 예외가 반환되는지 확인합니다. 빠른 동작 검증을 위해 TTL 설정을 줄인 결과는 공식 300초/30초 결과와 구분해야 합니다.

## 수집 결과

원본 파일은 `raw/`에 생성되고 Git에는 커밋되지 않습니다.

- k6 Summary JSON과 실행 로그
- Actuator Prometheus 원본 Metric
- 테스트 실행 중 1초 간격으로 수집한 Order Service와 Redis의 Docker CPU·Memory 기록
- 측정 Commit SHA, 장비, VU, 실행 시간과 반복 횟수
- WireMock 상품·배송 실제 호출 횟수

`summarize-k6.py`가 반복 실행 결과의 중앙값을 `raw/summary.md`에 생성합니다.

## 최종 결과표

공식 측정 완료 후 아래 표에 `raw/summary.md`의 중앙값을 옮기고 해석을 작성합니다.

| Variant | Scenario | 성공률 | 오류율 | RPS | p50 | p95 | p99 | LIVE | CACHE |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Before | 상품·배송 동시 장애 | 측정 전 | 측정 전 | 측정 전 | 측정 전 | 측정 전 | 측정 전 | 측정 전 | 측정 전 |
| After | 상품·배송 동시 장애 | 측정 전 | 측정 전 | 측정 전 | 측정 전 | 측정 전 | 측정 전 | 측정 전 | 측정 전 |

## 해석 시 주의사항

- 캐시 fallback은 장애 시 조회 가용성을 높이지만 데이터 최신성을 보장하지 않습니다.
- 캐시가 없거나 TTL이 만료된 경우 실패하는 것이 정상입니다.
- `fetchedAt`과 `external_cache_age_ms`를 함께 확인해 반환 데이터의 경과 시간을 기록합니다.
- 이 결과는 로컬 Docker의 동일 조건 비교이며 운영 환경의 최대 처리량을 의미하지 않습니다.
- 실제 반복 측정 전에는 성능 개선 수치를 이력서에 작성하지 않습니다.
