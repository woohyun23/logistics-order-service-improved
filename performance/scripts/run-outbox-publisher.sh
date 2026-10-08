#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPOSITORY_ROOT}/performance/docker-compose.yml"
MODE="${1:-compare}"
EVENT_COUNT="${EVENT_COUNT:-10000}"
REPEATS="${REPEATS:-3}"
INSTANCE_COUNTS="${INSTANCE_COUNTS:-1 2 4}"
PUBLISH_DELAY_MS="${OUTBOX_PUBLISH_DELAY_MS:-25}"
INITIAL_DELAY_MS="${OUTBOX_INITIAL_DELAY_MS:-10000}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
RESULT_DIR="${REPOSITORY_ROOT}/performance/results/outbox-publisher/raw/${RUN_ID}"
AUDIT_QUEUE="outbox.performance.audit"
RABBIT_API="http://localhost:15673"

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
    if curl -fsS "${url}" >/dev/null; then
      return
    fi
    sleep 2
  done
  echo "제한 시간 안에 준비되지 않았습니다: ${url}" >&2
  exit 1
}

rabbit_api() {
  local method="$1"
  local path="$2"
  local data="${3:-}"
  if [[ -n "${data}" ]]; then
    curl -fsS -u order:order -H 'content-type: application/json' \
      -X "${method}" "${RABBIT_API}${path}" -d "${data}" >/dev/null
  else
    curl -fsS -u order:order -X "${method}" "${RABBIT_API}${path}" >/dev/null
  fi
}

initialize_schema_and_exchange() {
  compose up -d --build postgres rabbitmq redis external-services order-service
  wait_for_http http://localhost:19090/actuator/health
  compose stop order-service >/dev/null
}

reset_rabbit_queues() {
  for queue in delivery.queue hub.queue notification.queue company.queue order.queue; do
    rabbit_api DELETE "/api/queues/%2F/${queue}/contents" || true
  done
  rabbit_api DELETE "/api/queues/%2F/${AUDIT_QUEUE}" || true
  rabbit_api PUT "/api/queues/%2F/${AUDIT_QUEUE}" \
    '{"durable":false,"auto_delete":false,"arguments":{}}'
  rabbit_api POST "/api/bindings/%2F/e/baekma.exchange/q/${AUDIT_QUEUE}" \
    '{"routing_key":"order.created","arguments":{}}'
}

seed_outbox() {
  compose exec -T postgres psql -v ON_ERROR_STOP=1 -U order -d logistics_order \
    -c 'TRUNCATE TABLE p_outbox_events' >/dev/null
  compose exec -T postgres psql -v ON_ERROR_STOP=1 -v event_count="${EVENT_COUNT}" \
    -U order -d logistics_order < "${REPOSITORY_ROOT}/performance/scripts/seed-outbox.sql" >/dev/null
}

publisher_container_ids() {
  compose --profile outbox ps -q outbox-publisher
}

publisher_port() {
  docker inspect --format '{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' "$1"
}

wait_for_publishers() {
  local expected="$1"
  local ids
  for _ in $(seq 1 90); do
    ids="$(publisher_container_ids)"
    if [[ "$(printf '%s\n' "${ids}" | sed '/^$/d' | wc -l | tr -d ' ')" == "${expected}" ]]; then
      local ready=0
      while IFS= read -r id; do
        [[ -z "${id}" ]] && continue
        if curl -fsS "http://localhost:$(publisher_port "${id}")/actuator/health" >/dev/null; then
          ready=$((ready + 1))
        fi
      done <<< "${ids}"
      [[ "${ready}" == "${expected}" ]] && return
    fi
    sleep 1
  done
  echo "Publisher ${expected}개가 제한 시간 안에 준비되지 않았습니다." >&2
  compose --profile outbox logs outbox-publisher
  exit 1
}

sample_runtime() {
  local prefix="$1"
  local ids="$2"
  local db_file="${prefix}-db-samples.csv"
  local stats_file="${prefix}-docker-stats.jsonl"
  local hikari_file="${prefix}-hikari-samples.csv"

  echo 'timestamp,connections,active_connections,waiting_locks' > "${db_file}"
  echo 'timestamp,container,hikari_active,hikari_max,process_cpu_usage,jvm_heap_used_bytes' > "${hikari_file}"
  : > "${stats_file}"

  while true; do
    local timestamp
    timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    local db_values
    db_values="$(compose exec -T postgres psql -At -U order -d logistics_order -c \
      "SELECT count(*), count(*) FILTER (WHERE state = 'active'), (SELECT count(*) FROM pg_locks WHERE NOT granted) FROM pg_stat_activity WHERE usename = 'order';" 2>/dev/null || echo '0|0|0')"
    echo "${timestamp},${db_values//|/,}" >> "${db_file}"

    docker stats --no-stream --format '{{json .}}' ${ids} >> "${stats_file}" 2>/dev/null || true

    while IFS= read -r id; do
      [[ -z "${id}" ]] && continue
      local port metrics active max process_cpu heap_used
      port="$(publisher_port "${id}")"
      metrics="$(curl -fsS "http://localhost:${port}/actuator/prometheus" 2>/dev/null || true)"
      active="$(printf '%s\n' "${metrics}" | awk '/^hikaricp_connections_active/ {print $NF; exit}')"
      max="$(printf '%s\n' "${metrics}" | awk '/^hikaricp_connections_max/ {print $NF; exit}')"
      process_cpu="$(printf '%s\n' "${metrics}" | awk '/^process_cpu_usage(\{| )/ {print $NF; exit}')"
      heap_used="$(printf '%s\n' "${metrics}" | awk '/^jvm_memory_used_bytes\{.*area="heap"/ {sum += $NF} END {print sum + 0}')"
      echo "${timestamp},${id:0:12},${active:-0},${max:-0},${process_cpu:-0},${heap_used:-0}" >> "${hikari_file}"
    done <<< "${ids}"
    sleep 1
  done
}

wait_until_published() {
  local deadline=$((SECONDS + TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    local pending
    pending="$(compose exec -T postgres psql -At -U order -d logistics_order \
      -c "SELECT count(*) FROM p_outbox_events WHERE status = 'PENDING';")"
    [[ "${pending}" == "0" ]] && return
    sleep 1
  done
  echo "${TIMEOUT_SECONDS}초 안에 모든 Outbox 이벤트가 처리되지 않았습니다." >&2
  return 1
}

metric_value() {
  local metrics="$1"
  local pattern="$2"
  printf '%s\n' "${metrics}" | awk -v pattern="${pattern}" '$0 ~ pattern {print $NF; exit}'
}

collect_publisher_metrics() {
  local ids="$1"
  local output="$2"
  : > "${output}"
  while IFS= read -r id; do
    [[ -z "${id}" ]] && continue
    local port metrics success failed claimed batch_count batch_sum
    port="$(publisher_port "${id}")"
    metrics="$(curl -fsS "http://localhost:${port}/actuator/prometheus")"
    success="$(metric_value "${metrics}" '^order_outbox_publish_total.*result="success"')"
    failed="$(metric_value "${metrics}" '^order_outbox_publish_total.*result="failed"')"
    claimed="$(metric_value "${metrics}" '^order_outbox_claimed_total')"
    batch_count="$(metric_value "${metrics}" '^order_operation_duration_seconds_count.*operation="outbox_publish_batch".*result="success"')"
    batch_sum="$(metric_value "${metrics}" '^order_operation_duration_seconds_sum.*operation="outbox_publish_batch".*result="success"')"
    jq -n \
      --arg container "${id:0:12}" \
      --argjson success "${success:-0}" \
      --argjson failed "${failed:-0}" \
      --argjson claimed "${claimed:-0}" \
      --argjson batchCount "${batch_count:-0}" \
      --argjson batchSeconds "${batch_sum:-0}" \
      '{container:$container,success:$success,failed:$failed,claimed:$claimed,batchCount:$batchCount,batchSeconds:$batchSeconds}' \
      >> "${output}"
  done <<< "${ids}"
}

drain_audit_queue() {
  local output="$1"
  local empty_polls=0
  : > "${output}"
  while true; do
    local response_file received
    response_file="$(mktemp)"
    curl -fsS -u order:order -H 'content-type: application/json' \
      -X POST "${RABBIT_API}/api/queues/%2F/${AUDIT_QUEUE}/get" \
      -d '{"count":5000,"ackmode":"ack_requeue_false","encoding":"auto","truncate":500000}' \
      > "${response_file}"
    received="$(jq 'length' "${response_file}")"
    if (( received > 0 )); then
      jq -r '.[] | .payload | fromjson | .header.messageId' "${response_file}" >> "${output}"
      empty_polls=0
    else
      empty_polls=$((empty_polls + 1))
    fi
    rm -f "${response_file}"
    (( empty_polls >= 5 )) && break
    (( received == 0 )) && sleep 1
  done
}

max_csv_column() {
  local file="$1"
  local column="$2"
  awk -F, -v column="${column}" 'NR > 1 && $column + 0 > max {max=$column + 0} END {print max + 0}' "${file}"
}

run_case() {
  local variant="$1"
  local skip_locked="$2"
  local instances="$3"
  local run_number="$4"
  local prefix="${RESULT_DIR}/${variant}-instances-${instances}-run-$(printf '%02d' "${run_number}")"

  compose --profile outbox rm -sf outbox-publisher >/dev/null 2>&1 || true
  reset_rabbit_queues
  seed_outbox

  OUTBOX_SKIP_LOCKED_ENABLED="${skip_locked}" \
  OUTBOX_PUBLISH_DELAY_MS="${PUBLISH_DELAY_MS}" \
  OUTBOX_INITIAL_DELAY_MS="${INITIAL_DELAY_MS}" \
    compose --profile outbox up -d --no-deps --force-recreate \
      --scale outbox-publisher="${instances}" outbox-publisher

  local ids sampler_pid
  ids="$(publisher_container_ids)"
  sample_runtime "${prefix}" "${ids}" &
  sampler_pid=$!
  wait_for_publishers "${instances}"
  wait_until_published
  sleep 2

  kill "${sampler_pid}" 2>/dev/null || true
  wait "${sampler_pid}" 2>/dev/null || true

  collect_publisher_metrics "${ids}" "${prefix}-publishers.jsonl"
  drain_audit_queue "${prefix}-message-ids.txt"

  local received unique duplicates lost published failed pending duration_ms throughput
  received="$(wc -l < "${prefix}-message-ids.txt" | tr -d ' ')"
  unique="$(sort -u "${prefix}-message-ids.txt" | wc -l | tr -d ' ')"
  duplicates=$((received - unique))
  lost=$((EVENT_COUNT - unique))
  read -r published failed pending duration_ms <<< "$(compose exec -T postgres psql -At -F ' ' -U order -d logistics_order -c \
    "SELECT count(*) FILTER (WHERE status='PUBLISHED'), count(*) FILTER (WHERE status='FAILED'), count(*) FILTER (WHERE status='PENDING'), COALESCE(round(EXTRACT(EPOCH FROM (max(published_at)-min(published_at))) * 1000),0) FROM p_outbox_events;")"
  throughput="$(awk -v count="${EVENT_COUNT}" -v duration="${duration_ms}" 'BEGIN {if (duration > 0) printf "%.2f", count/(duration/1000); else print "0"}')"

  local max_connections max_active_connections max_waiting_locks max_hikari max_process_cpu max_jvm_heap
  max_connections="$(max_csv_column "${prefix}-db-samples.csv" 2)"
  max_active_connections="$(max_csv_column "${prefix}-db-samples.csv" 3)"
  max_waiting_locks="$(max_csv_column "${prefix}-db-samples.csv" 4)"
  max_hikari="$(max_csv_column "${prefix}-hikari-samples.csv" 3)"
  max_process_cpu="$(max_csv_column "${prefix}-hikari-samples.csv" 5)"
  max_jvm_heap="$(max_csv_column "${prefix}-hikari-samples.csv" 6)"

  jq -n \
    --arg variant "${variant}" \
    --argjson instances "${instances}" \
    --argjson run "${run_number}" \
    --argjson initialEvents "${EVENT_COUNT}" \
    --argjson received "${received}" \
    --argjson unique "${unique}" \
    --argjson duplicates "${duplicates}" \
    --argjson lost "${lost}" \
    --argjson published "${published}" \
    --argjson failed "${failed}" \
    --argjson pending "${pending}" \
    --argjson durationMs "${duration_ms}" \
    --argjson throughput "${throughput}" \
    --argjson maxConnections "${max_connections}" \
    --argjson maxActiveConnections "${max_active_connections}" \
    --argjson maxWaitingLocks "${max_waiting_locks}" \
    --argjson maxHikariActive "${max_hikari}" \
    --argjson maxProcessCpuUsage "${max_process_cpu}" \
    --argjson maxJvmHeapUsedBytes "${max_jvm_heap}" \
    --slurpfile publishers "${prefix}-publishers.jsonl" \
    '{variant:$variant,instances:$instances,run:$run,initialEvents:$initialEvents,received:$received,unique:$unique,duplicates:$duplicates,lost:$lost,published:$published,failed:$failed,pending:$pending,durationMs:$durationMs,eventsPerSecond:$throughput,maxConnections:$maxConnections,maxActiveConnections:$maxActiveConnections,maxWaitingLocks:$maxWaitingLocks,maxHikariActive:$maxHikariActive,maxProcessCpuUsage:$maxProcessCpuUsage,maxJvmHeapUsedBytes:$maxJvmHeapUsedBytes,publishers:$publishers}' \
    > "${prefix}.json"

  echo "${variant} publishers=${instances} run=${run_number}: ${throughput} events/s, duplicates=${duplicates}, lost=${lost}"
}

write_manifest() {
  {
    echo "measured_commit=$(git -C "${REPOSITORY_ROOT}" rev-parse HEAD)"
    echo "historical_pre_skip_locked_commit=833d0e772e2c4a5e522da6a69a858b85273cbae2"
    echo "skip_locked_feature_commit=444034f"
    echo "working_tree_dirty=$([[ -n "$(git -C "${REPOSITORY_ROOT}" status --porcelain)" ]] && echo true || echo false)"
    echo "measured_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "mode=${MODE}"
    echo "event_count=${EVENT_COUNT}"
    echo "repeats=${REPEATS}"
    echo "instance_counts=${INSTANCE_COUNTS}"
    echo "publish_delay_ms=${PUBLISH_DELAY_MS}"
    echo "initial_delay_ms=${INITIAL_DELAY_MS}"
    echo "docker=$(docker version --format '{{.Server.Version}}')"
    echo "compose=$(docker compose version --short)"
    echo "system=$(uname -a)"
  } > "${RESULT_DIR}/manifest.txt"
}

require_commands
require_clean_commit
mkdir -p "${RESULT_DIR}"

if [[ "${MODE}" == "validate" ]]; then
  EVENT_COUNT="${VALIDATION_EVENT_COUNT:-500}"
  REPEATS=1
  INSTANCE_COUNTS="${VALIDATION_INSTANCE_COUNTS:-1 2}"
elif [[ "${MODE}" != "compare" ]]; then
  echo "사용법: $0 [compare|validate]" >&2
  exit 1
fi

initialize_schema_and_exchange
write_manifest

for run_number in $(seq 1 "${REPEATS}"); do
  for instances in ${INSTANCE_COUNTS}; do
    run_case before false "${instances}" "${run_number}"
    run_case after true "${instances}" "${run_number}"
  done
done

python3 "${REPOSITORY_ROOT}/performance/scripts/summarize-outbox.py" \
  "${RESULT_DIR}" --output "${RESULT_DIR}/summary.md"

compose --profile outbox rm -sf outbox-publisher >/dev/null 2>&1 || true
echo "측정 완료: ${RESULT_DIR}"
