#!/usr/bin/env python3
import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path


def median(rows, field):
    return statistics.median(float(row[field]) for row in rows)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("result_dir", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    grouped = defaultdict(list)
    for path in sorted(args.result_dir.glob("*-instances-*-run-*.json")):
        with path.open() as file:
            result = json.load(file)
        grouped[(result["variant"], result["instances"])].append(result)

    lines = [
        "# 다중 Outbox Publisher 반복 측정 요약",
        "",
        "각 값은 같은 Variant와 Publisher 수로 실행한 결과의 중앙값입니다.",
        "",
        "| Variant | Publishers | Runs | 처리 시간(ms) | events/s | 수신 | 고유 | 중복 | 유실 | DB 연결 | DB 활성 | Lock 대기 | Hikari 활성 | Process CPU(%) | JVM Heap(MiB) |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]

    for (variant, instances), rows in sorted(grouped.items()):
        lines.append(
            f"| {variant} | {instances} | {len(rows)} "
            f"| {median(rows, 'durationMs'):.0f} "
            f"| {median(rows, 'eventsPerSecond'):.2f} "
            f"| {median(rows, 'received'):.0f} "
            f"| {median(rows, 'unique'):.0f} "
            f"| {median(rows, 'duplicates'):.0f} "
            f"| {median(rows, 'lost'):.0f} "
            f"| {median(rows, 'maxConnections'):.0f} "
            f"| {median(rows, 'maxActiveConnections'):.0f} "
            f"| {median(rows, 'maxWaitingLocks'):.0f} "
            f"| {median(rows, 'maxHikariActive'):.0f} "
            f"| {median(rows, 'maxProcessCpuUsage') * 100:.2f} "
            f"| {median(rows, 'maxJvmHeapUsedBytes') / 1024 / 1024:.2f} |"
        )

    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
