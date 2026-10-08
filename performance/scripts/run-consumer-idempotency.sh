#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPOSITORY_ROOT}/performance/docker-compose.yml"
TEMPLATE_FILE="${REPOSITORY_ROOT}/performance/message-generator/templates/delivery-created.json"
MODE="${1:-compare}"
REPEATS="${REPEATS:-3}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-180}"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
RESULT_DIR="${REPOSITORY_ROOT}/performance/results/consumer-idempotency/raw/${RUN_ID}"
RABBIT_API="http://localhost:15673"
ORDER_ID="10000000-0000-0000-0000-000000000001"
DELIVERY_ID="30000000-0000-0000-0000-000000000001"

compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

require_commands() {
  for command in curl docker jq python3; do
    command -v "${command}" >/dev/null || {
      echo "필수 명령을 찾을 수 없습니다: ${command}" >&2
      exit 1
    }
  done
}

require_clean_commit() {
  if [[ "${ALLOW_DIRTY:-false}" != "true" ]] && [[ -n "$(git -C "${REPOSITORY_ROOT}" status --porcelain)" ]]; then
    echo "공식 측정은 변경 사항을 커밋한 뒤 실행해야 합니다. 임시 검증은 ALLOW_DIRTY=true를 사용하세요." >&2
    exit 1
  fi
}

wait_for_http() {
  local url="$1"
  for _ in $(seq 1 90); do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      return
    fi
    sleep 2
  done
  echo "제한 시간 안에 준비되지 않았습니다: ${url}" >&2
  exit 1
}

rabbit_queue_value() {
  local field="$1"
  curl -fsS -u order:order "${RABBIT_API}/api/queues/%2F/order.queue" | jq -r ".${field} // 0"
}

purge_order_queue() {
  curl -fsS -u order:order -X DELETE \
    "${RABBIT_API}/api/queues/%2F/order.queue/contents" >/dev/null 2>&1 || true
}

initialize_environment() {
  compose up -d --build postgres rabbitmq redis external-services order-service
  wait_for_http http://localhost:19090/actuator/health
  compose stop order-service >/dev/null
  bash "${REPOSITORY_ROOT}/gradlew" --quiet performanceClasses
}

reset_database() {
  compose exec -T postgres psql -v ON_ERROR_STOP=1 -U order -d logistics_order \
    -c 'TRUNCATE TABLE p_processed_events, p_outbox_events, p_orders' >/dev/null
}

seed_order() {
  compose exec -T postgres psql -v ON_ERROR_STOP=1 -v order_id="${ORDER_ID}" \
    -U order -d logistics_order < "${SCRIPT_DIR}/seed-consumer-order.sql" >/dev/null
}

start_fresh_consumer() {
  local seed="$1"
  compose rm -sf order-service >/dev/null 2>&1 || true
  purge_order_queue
  reset_database
  [[ "${seed}" == "true" ]] && seed_order
  RABBITMQ_CONSUMER_CONCURRENCY="${CONSUMER_CONCURRENCY}" \
  RABBITMQ_CONSUMER_MAX_CONCURRENCY="${CONSUMER_MAX_CONCURRENCY}" \
  RABBITMQ_DEFAULT_REQUEUE_REJECTED=false \
    compose up -d --no-deps --force-recreate order-service
  wait_for_http http://localhost:19090/actuator/health
}

metric_value() {
  local metrics="$1"
  local pattern="$2"
  printf '%s\n' "${metrics}" | awk -v pattern="${pattern}" '$0 ~ pattern {print $NF; exit}'
}

consumer_count() {
  local result="$1"
  local metrics value
  metrics="$(curl -fsS http://localhost:19090/actuator/prometheus)"
  value="$(metric_value "${metrics}" "^order_consumer_event_total.*result=\"${result}\"")"
  printf '%.0f' "${value:-0}"
}

wait_until_consumed() {
  local expected="$1"
  local deadline=$((SECONDS + TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    local processed duplicate failed ready unacked total
    processed="$(consumer_count processed)"
    duplicate="$(consumer_count duplicate)"
    failed="$(consumer_count failed)"
    ready="$(rabbit_queue_value messages_ready)"
    unacked="$(rabbit_queue_value messages_unacknowledged)"
    total=$((processed + duplicate + failed))
    if (( total >= expected && ready == 0 && unacked == 0 )); then
      return
    fi
    sleep 1
  done
  echo "${TIMEOUT_SECONDS}초 안에 Consumer 처리가 끝나지 않았습니다. expected=${expected}" >&2
  return 1
}

sample_runtime() {
  local output="$1"
  echo 'timestamp,queue_ready,queue_unacked,hikari_active,db_connections' > "${output}"
  while true; do
    local timestamp queue_json ready unacked metrics hikari db_connections
    timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    queue_json="$(curl -fsS -u order:order "${RABBIT_API}/api/queues/%2F/order.queue" 2>/dev/null || echo '{}')"
    ready="$(printf '%s' "${queue_json}" | jq -r '.messages_ready // 0')"
    unacked="$(printf '%s' "${queue_json}" | jq -r '.messages_unacknowledged // 0')"
    metrics="$(curl -fsS http://localhost:19090/actuator/prometheus 2>/dev/null || true)"
    hikari="$(metric_value "${metrics}" '^hikaricp_connections_active')"
    db_connections="$(compose exec -T postgres psql -At -U order -d logistics_order \
      -c "SELECT count(*) FROM pg_stat_activity WHERE usename='order';" 2>/dev/null || echo 0)"
    echo "${timestamp},${ready},${unacked},${hikari:-0},${db_connections:-0}" >> "${output}"
    sleep 0.5
  done
}

run_generator() {
  local count="$1"
  local concurrency="$2"
  local mode="$3"
  local event_id="$4"
  local unique_count="$5"
  local output="$6"
  RABBITMQ_HOST=localhost \
  RABBITMQ_PORT=15672 \
  RABBITMQ_USERNAME=order \
  RABBITMQ_PASSWORD=order \
  MESSAGE_EXCHANGE=baekma.exchange \
  MESSAGE_ROUTING_KEY=delivery.created \
  MESSAGE_COUNT="${count}" \
  MESSAGE_CONCURRENCY="${concurrency}" \
  MESSAGE_ID_MODE="${mode}" \
  FIXED_MESSAGE_ID="${event_id}" \
  UNIQUE_MESSAGE_COUNT="${unique_count}" \
  MESSAGE_ID_SEED="${event_id}" \
  ORDER_ID="${ORDER_ID}" \
  DELIVERY_ID="${DELIVERY_ID}" \
  MESSAGE_TEMPLATE_FILE="${TEMPLATE_FILE}" \
    bash "${REPOSITORY_ROOT}/gradlew" --quiet runPerformanceMessageGenerator > "${output}"
}

database_value() {
  local query="$1"
  compose exec -T postgres psql -At -U order -d logistics_order -c "${query}"
}

max_csv_column() {
  local file="$1"
  local column="$2"
  awk -F, -v column="${column}" 'NR > 1 && $column + 0 > max {max=$column + 0} END {print max + 0}' "${file}"
}

collect_result() {
  local scenario="$1"
  local run_number="$2"
  local expected_received="$3"
  local expected_processed="$4"
  local expected_duplicate="$5"
  local expected_failed="$6"
  local duration_ms="$7"
  local prefix="$8"

  local metrics processed duplicate failed history_count business_changes p50 p95 p99
  metrics="$(curl -fsS http://localhost:19090/actuator/prometheus)"
  processed="$(consumer_count processed)"
  duplicate="$(consumer_count duplicate)"
  failed="$(consumer_count failed)"
  history_count="$(database_value 'SELECT count(*) FROM p_processed_events;')"
  business_changes="$(database_value "SELECT count(*) FROM p_orders WHERE delivery_id='${DELIVERY_ID}'::uuid;")"
  p50="$(metric_value "${metrics}" '^order_consumer_duration_seconds.*quantile="0.5"')"
  p95="$(metric_value "${metrics}" '^order_consumer_duration_seconds.*quantile="0.95"')"
  p99="$(metric_value "${metrics}" '^order_consumer_duration_seconds.*quantile="0.99"')"

  local max_ready max_unacked max_hikari max_db invariant
  max_ready="$(max_csv_column "${prefix}-runtime.csv" 2)"
  max_unacked="$(max_csv_column "${prefix}-runtime.csv" 3)"
  max_hikari="$(max_csv_column "${prefix}-runtime.csv" 4)"
  max_db="$(max_csv_column "${prefix}-runtime.csv" 5)"
  invariant=false
  if (( processed == expected_processed && duplicate == expected_duplicate && failed == expected_failed \
        && history_count == expected_processed && business_changes == 1 \
        && processed + duplicate + failed == expected_received )); then
    invariant=true
  fi

  jq -n \
    --arg scenario "${scenario}" \
    --argjson run "${run_number}" \
    --argjson received "$((processed + duplicate + failed))" \
    --argjson processed "${processed}" \
    --argjson duplicate "${duplicate}" \
    --argjson failed "${failed}" \
    --argjson historyCount "${history_count}" \
    --argjson businessChanges "${business_changes}" \
    --argjson durationMs "${duration_ms}" \
    --argjson p50Ms "$(awk -v value="${p50:-0}" 'BEGIN {print value * 1000}')" \
    --argjson p95Ms "$(awk -v value="${p95:-0}" 'BEGIN {print value * 1000}')" \
    --argjson p99Ms "$(awk -v value="${p99:-0}" 'BEGIN {print value * 1000}')" \
    --argjson maxQueueReady "${max_ready}" \
    --argjson maxQueueUnacked "${max_unacked}" \
    --argjson maxHikariActive "${max_hikari}" \
    --argjson maxDbConnections "${max_db}" \
    --argjson invariantSatisfied "${invariant}" \
    '{scenario:$scenario,run:$run,received:$received,processed:$processed,duplicate:$duplicate,failed:$failed,historyCount:$historyCount,businessChanges:$businessChanges,durationMs:$durationMs,p50Ms:$p50Ms,p95Ms:$p95Ms,p99Ms:$p99Ms,maxQueueReady:$maxQueueReady,maxQueueUnacked:$maxQueueUnacked,maxHikariActive:$maxHikariActive,maxDbConnections:$maxDbConnections,invariantSatisfied:$invariantSatisfied}' \
    > "${prefix}.json"

  [[ "${invariant}" == "true" ]] || {
    echo "불변 조건 실패: scenario=${scenario} run=${run_number}" >&2
    return 1
  }
  echo "${scenario} run=${run_number}: processed=${processed}, duplicate=${duplicate}, failed=${failed}, p95=${p95:-0}s"
}

run_standard_case() {
  local scenario="$1" count="$2" concurrency="$3" mode="$4" unique_count="$5"
  local expected_processed="$6" expected_duplicate="$7" run_number="$8"
  local prefix="${RESULT_DIR}/${scenario}-run-$(printf '%02d' "${run_number}")"
  start_fresh_consumer true

  sample_runtime "${prefix}-runtime.csv" &
  local sampler_pid=$!
  local started_at finished_at
  started_at="$(python3 -c 'import time; print(time.time_ns())')"
  run_generator "${count}" "${concurrency}" "${mode}" "${scenario}-${run_number}" \
    "${unique_count}" "${prefix}-generator.txt"
  wait_until_consumed "${count}"
  finished_at="$(python3 -c 'import time; print(time.time_ns())')"
  kill "${sampler_pid}" 2>/dev/null || true
  wait "${sampler_pid}" 2>/dev/null || true

  collect_result "${scenario}" "${run_number}" "${count}" "${expected_processed}" \
    "${expected_duplicate}" 0 "$(((finished_at - started_at) / 1000000))" "${prefix}"
}

run_failure_retry_case() {
  local run_number="$1"
  local scenario="failure-retry"
  local prefix="${RESULT_DIR}/${scenario}-run-$(printf '%02d' "${run_number}")"
  local event_id="${scenario}-${run_number}"
  start_fresh_consumer false

  sample_runtime "${prefix}-runtime.csv" &
  local sampler_pid=$!
  local started_at finished_at history_after_failure business_after_failure
  started_at="$(python3 -c 'import time; print(time.time_ns())')"
  run_generator 1 1 fixed "${event_id}" 1 "${prefix}-failure-generator.txt"
  wait_until_consumed 1
  history_after_failure="$(database_value 'SELECT count(*) FROM p_processed_events;')"
  business_after_failure="$(database_value 'SELECT count(*) FROM p_orders;')"

  seed_order
  run_generator 1 1 fixed "${event_id}" 1 "${prefix}-retry-generator.txt"
  wait_until_consumed 2
  finished_at="$(python3 -c 'import time; print(time.time_ns())')"
  kill "${sampler_pid}" 2>/dev/null || true
  wait "${sampler_pid}" 2>/dev/null || true

  if [[ "${history_after_failure}" != "0" || "${business_after_failure}" != "0" ]]; then
    echo "실패 트랜잭션 롤백 검증 실패: history=${history_after_failure}, orders=${business_after_failure}" >&2
    return 1
  fi
  collect_result "${scenario}" "${run_number}" 2 1 0 1 \
    "$(((finished_at - started_at) / 1000000))" "${prefix}"

  local enriched_result
  enriched_result="$(mktemp)"
  jq \
    --argjson historyAfterFailure "${history_after_failure}" \
    --argjson businessRowsAfterFailure "${business_after_failure}" \
    '. + {
      historyAfterFailure: $historyAfterFailure,
      businessRowsAfterFailure: $businessRowsAfterFailure,
      rollbackVerified: ($historyAfterFailure == 0 and $businessRowsAfterFailure == 0),
      retryProcessed: (.processed == 1 and .historyCount == 1 and .businessChanges == 1)
    }' "${prefix}.json" > "${enriched_result}"
  mv "${enriched_result}" "${prefix}.json"
}

write_manifest() {
  {
    echo "measured_commit=$(git -C "${REPOSITORY_ROOT}" rev-parse HEAD)"
    echo "working_tree_dirty=$([[ -n "$(git -C "${REPOSITORY_ROOT}" status --porcelain)" ]] && echo true || echo false)"
    echo "measured_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "mode=${MODE}"
    echo "repeats=${REPEATS}"
    echo "consumer_concurrency=${CONSUMER_CONCURRENCY}"
    echo "consumer_max_concurrency=${CONSUMER_MAX_CONCURRENCY}"
    echo "docker=$(docker version --format '{{.Server.Version}}')"
    echo "compose=$(docker compose version --short)"
    echo "system=$(uname -a)"
  } > "${RESULT_DIR}/manifest.txt"
}

require_commands
require_clean_commit

CONSUMER_CONCURRENCY="${RABBITMQ_CONSUMER_CONCURRENCY:-4}"
CONSUMER_MAX_CONCURRENCY="${RABBITMQ_CONSUMER_MAX_CONCURRENCY:-16}"
if [[ "${MODE}" == "validate" ]]; then
  REPEATS=1
elif [[ "${MODE}" != "compare" ]]; then
  echo "사용법: $0 [compare|validate]" >&2
  exit 1
fi

mkdir -p "${RESULT_DIR}"
initialize_environment
write_manifest

for run_number in $(seq 1 "${REPEATS}"); do
  run_standard_case sequential-100 100 1 fixed 1 1 99 "${run_number}"
  run_standard_case concurrent-100 100 100 fixed 1 1 99 "${run_number}"
  run_standard_case concurrent-1000 1000 100 fixed 1 1 999 "${run_number}"
  run_standard_case mixed-1000 1000 100 cyclic 500 500 500 "${run_number}"
  run_failure_retry_case "${run_number}"
done

python3 "${SCRIPT_DIR}/summarize-consumer-idempotency.py" \
  "${RESULT_DIR}" --output "${RESULT_DIR}/summary.md"
compose stop order-service >/dev/null
echo "측정 완료: ${RESULT_DIR}"
