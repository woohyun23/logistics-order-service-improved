# 부하테스트 결과 저장 기준

시나리오별 원본 결과는 로컬에만 보관하고, 재현에 필요한 조건과 중앙값을 정리한 `README.md`만 Git에 커밋합니다.

## 디렉터리

```text
results/
├─ cache-fallback/
├─ outbox-publisher/
└─ consumer-idempotency/
```

각 디렉터리에는 다음 형식으로 원본 파일을 저장합니다.

- `YYYYMMDD-HHmm-before-run-01.json`
- `YYYYMMDD-HHmm-after-run-01.json`
- Prometheus snapshot 또는 쿼리 결과가 필요하면 같은 접두어를 사용한 `.json` 또는 `.csv`

원본 결과는 `.gitignore` 대상입니다. 시나리오별 `README.md`에는 아래 항목을 기록합니다.

## 요약 보고서 템플릿

```markdown
# <시나리오 이름>

## 측정 목적
- 비교하려는 변경 사항:
- Before Commit:
- After Commit:

## 실행 환경
- 실행 일시:
- CPU / Memory:
- OS / Docker 버전:
- JVM 옵션:
- 컨테이너별 자원 제한:

## 부하 조건
- 데이터 건수:
- VU 또는 Producer 동시성:
- Warm-up 시간:
- 본 측정 시간:
- 반복 횟수:

## 결과

| 구분 | RPS/TPS | 성공률 | 오류율 | p50 | p95 | p99 | CPU | Heap/GC |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Before 중앙값 | | | | | | | | |
| After 중앙값 | | | | | | | | |

## 해석
- 변경 사항이 직접 영향을 준 지표:
- 트레이드오프:
- 운영 환경에 일반화할 수 없는 조건:
```

가장 좋은 한 번의 결과가 아니라 Warm-up 이후 최소 3회 본 측정의 중앙값을 사용합니다.
