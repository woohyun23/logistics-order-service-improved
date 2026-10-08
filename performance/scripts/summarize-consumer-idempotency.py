#!/usr/bin/env python3
import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path


FIELDS = (
    "received",
    "processed",
    "duplicate",
    "failed",
    "historyCount",
    "businessChanges",
    "durationMs",
    "p50Ms",
    "p95Ms",
    "p99Ms",
    "maxQueueReady",
    "maxQueueUnacked",
    "maxHikariActive",
    "maxDbConnections",
)


def median(rows, field):
    return statistics.median(float(row[field]) for row in rows)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("result_dir", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    grouped = defaultdict(list)
    for path in sorted(args.result_dir.glob("*-run-*.json")):
        with path.open(encoding="utf-8") as file:
            result = json.load(file)
        grouped[result["scenario"]].append(result)

    lines = [
        "# Consumer 멱등성 반복 측정 요약",
        "",
        "각 값은 같은 시나리오를 반복 실행한 결과의 중앙값입니다.",
        "",
        "| 시나리오 | Runs | 수신 | 처리 | 중복 차단 | 실패 | 처리 이력 | 상태 변경 | 완료(ms) | p50(ms) | p95(ms) | p99(ms) | Queue Ready | Queue Unacked | Hikari | DB 연결 | 불변 조건 |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|",
    ]

    for scenario, rows in sorted(grouped.items()):
        values = {field: median(rows, field) for field in FIELDS}
        invariant = all(row["invariantSatisfied"] for row in rows)
        lines.append(
            f"| {scenario} | {len(rows)} "
            f"| {values['received']:.0f} | {values['processed']:.0f} "
            f"| {values['duplicate']:.0f} | {values['failed']:.0f} "
            f"| {values['historyCount']:.0f} | {values['businessChanges']:.0f} "
            f"| {values['durationMs']:.0f} | {values['p50Ms']:.3f} "
            f"| {values['p95Ms']:.3f} | {values['p99Ms']:.3f} "
            f"| {values['maxQueueReady']:.0f} | {values['maxQueueUnacked']:.0f} "
            f"| {values['maxHikariActive']:.0f} | {values['maxDbConnections']:.0f} "
            f"| {'충족' if invariant else '실패'} |"
        )

    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
