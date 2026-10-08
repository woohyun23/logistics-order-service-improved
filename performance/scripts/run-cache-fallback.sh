#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPOSITORY_ROOT}/performance/docker-compose.yml"
RESULT_DIR="${REPOSITORY_ROOT}/performance/results/cache-fallback/raw"
MODE="${1:-compare}"
REPEATS="${REPEATS:-3}"
VUS="${VUS:-10}"
DURATION="${DURATION:-20s}"
ORDER_COUNT="${ORDER_COUNT:-100}"

compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

require_clean_commit() {
  if [[ "${ALLOW_DIRTY:-false}" != "true" ]] && [[ -n "$(git -C "${REPOSITORY_ROOT}" status --porcelain)" ]]; then
    echo "공식 측정은 변경 사항을 커밋한 뒤 실행해야 합니다. 임시 검증은 ALLOW_DIRTY=true를 사용하세요." >&2
    exit 1
  fi
}

wait_for_order_service() {
  for _ in $(seq 1 60); do
    if curl -fsS http://localhost:19090/actuator/health >/dev/null; then
      return
    fi
    sleep 2
  done
  echo "Order Service가 제한 시간 안에 준비되지 않았습니다." >&2
  compose logs order-service
  exit 1
}

restart_order_service() {
  local enabled="$1"
  CACHE_FALLBACK_ENABLED="${enabled}" compose up -d --no-deps --force-recreate order-service
  wait_for_order_service
}

reset_data() {
  compose exec -T postgres psql -v ON_ERROR_STOP=1 -U order -d logistics_order \
    < "${REPOSITORY_ROOT}/performance/scripts/reset.sql"
  compose exec -T postgres psql -v ON_ERROR_STOP=1 -U order -d logistics_order \
    < "${REPOSITORY_ROOT}/performance/scripts/seed-orders.sql"
}

flush_cache() {
  compose exec -T redis redis-cli FLUSHDB >/dev/null
}

sample_container_stats() {
  local stats_file="$1"
  local order_container="$2"
  local redis_container="$3"

  while true; do
    docker stats --no-stream --format '{{json .}}' \
      "${order_container}" "${redis_container}" >> "${stats_file}" || true
    sleep 1
  done
}

run_k6() {
  local variant="$1"
  local scenario="$2"
  local run_number="$3"
  local script="$4"
  shift 4

  local prefix="${variant}-${scenario}-run-$(printf '%02d' "${run_number}")"
  local stats_file="${RESULT_DIR}/${prefix}-docker-stats.jsonl"
  local stats_pid
  local k6_status

  : > "${stats_file}"
  sample_container_stats \
    "${stats_file}" \
    "$(compose ps -q order-service)" \
    "$(compose ps -q redis)" &
  stats_pid=$!

  set +e
  compose --profile tools run --rm --no-deps \
    -e VUS="${VUS}" \
    -e DURATION="${DURATION}" \
    -e ORDER_COUNT="${ORDER_COUNT}" \
    -e K6_SUMMARY_TREND_STATS="avg,min,med,p(90),p(95),p(99),max" \
    "$@" \
    k6 run \
    --tag variant="${variant}" \
    --tag scenario="${scenario}" \
    --summary-export "/results/cache-fallback/raw/${prefix}.json" \
    "/scripts/cache-fallback/${script}" \
    2>&1 | tee "${RESULT_DIR}/${prefix}.log"
  k6_status=${PIPESTATUS[0]}
  set -e

  kill "${stats_pid}" 2>/dev/null || true
  wait "${stats_pid}" 2>/dev/null || true

  curl -fsS http://localhost:19090/actuator/prometheus \
    > "${RESULT_DIR}/${prefix}-metrics.prom"

  return "${k6_status}"
}

warm_cache() {
  compose --profile tools run --rm --no-deps \
    -e VUS="${VUS}" \
    -e ORDER_COUNT="${ORDER_COUNT}" \
    k6 run /scripts/cache-fallback/warmup.js >/dev/null
}

prepare_case() {
  local fallback_enabled="$1"
  restart_order_service "${fallback_enabled}"
  flush_cache
}

run_variant() {
  local variant="$1"
  local fallback_enabled="$2"
  local run_number="$3"

  prepare_case "${fallback_enabled}"
  run_k6 "${variant}" normal "${run_number}" normal.js

  for target in product delivery both; do
    prepare_case "${fallback_enabled}"
    warm_cache
    run_k6 "${variant}" "${target}-error" "${run_number}" fault.js \
      -e FAULT_TARGET="${target}" -e FAULT_MODE=SERVER_ERROR
  done

  for fault_mode in TIMEOUT CONNECTION_FAILURE; do
    local scenario_mode
    scenario_mode="$(printf '%s' "${fault_mode}" | tr '[:upper:]' '[:lower:]')"
    prepare_case "${fallback_enabled}"
    warm_cache
    run_k6 "${variant}" "both-${scenario_mode}" "${run_number}" fault.js \
      -e FAULT_TARGET=both -e FAULT_MODE="${fault_mode}"
  done

  prepare_case "${fallback_enabled}"
  run_k6 "${variant}" cache-miss "${run_number}" cache-miss.js

  prepare_case "${fallback_enabled}"
  run_k6 "${variant}" recovery "${run_number}" recovery.js
}

run_ttl_checks() {
  prepare_case true
  run_k6 after delivery-ttl-expired 1 ttl-expiration.js \
    -e TTL_TARGET=delivery -e TTL_SECONDS="${DELIVERY_CACHE_TTL_SECONDS:-30}"

  prepare_case true
  run_k6 after product-ttl-expired 1 ttl-expiration.js \
    -e TTL_TARGET=product -e TTL_SECONDS="${PRODUCT_CACHE_TTL_SECONDS:-300}"
}

write_manifest() {
  {
    echo "measured_commit=$(git -C "${REPOSITORY_ROOT}" rev-parse HEAD)"
    echo "historical_pre_fallback_commit=119d208bede6aef561d5b9a0b99c6284a825e845"
    echo "fallback_feature_commit=4375ca558f6ff5e3a8e501def8e580542ad71f75"
    echo "working_tree_dirty=$([[ -n "$(git -C "${REPOSITORY_ROOT}" status --porcelain)" ]] && echo true || echo false)"
    echo "measured_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "mode=${MODE}"
    echo "repeats=${REPEATS}"
    echo "vus=${VUS}"
    echo "duration=${DURATION}"
    echo "order_count=${ORDER_COUNT}"
    echo "docker=$(docker version --format '{{.Server.Version}}')"
    echo "compose=$(docker compose version --short)"
    echo "system=$(uname -a)"
  } > "${RESULT_DIR}/manifest.txt"
}

require_clean_commit
mkdir -p "${RESULT_DIR}"
compose up -d --build
wait_for_order_service
reset_data
write_manifest

case "${MODE}" in
  compare)
    for run_number in $(seq 1 "${REPEATS}"); do
      run_variant before false "${run_number}"
      run_variant after true "${run_number}"
    done
    ;;
  ttl)
    run_ttl_checks
    ;;
  validate)
    REPEATS=1
    VUS=2
    DURATION=3s
    run_variant after true 1
    ;;
  *)
    echo "사용법: $0 [compare|ttl|validate]" >&2
    exit 1
    ;;
esac

python3 "${REPOSITORY_ROOT}/performance/scripts/summarize-k6.py" \
  "${RESULT_DIR}" \
  --output "${RESULT_DIR}/summary.md"

echo "측정 완료: ${RESULT_DIR}"
echo "공식 결과를 작성할 때 raw/summary.md의 중앙값을 검토한 뒤 상위 README.md에 옮기세요."
