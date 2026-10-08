#!/usr/bin/env python3
import argparse
import json
import re
import statistics
from collections import defaultdict
from pathlib import Path


FILE_PATTERN = re.compile(r"^(before|after)-(.+)-run-\d+\.json$")


def metric_value(metrics, name, key, default=0.0):
    metric = metrics.get(name, {})
    values = metric.get("values", metric)
    if key == "rate" and "rate" not in values and "value" in values:
        key = "value"
    return float(values.get(key, default))


def load_results(result_dir):
    grouped = defaultdict(list)
    for path in sorted(result_dir.glob("*.json")):
        match = FILE_PATTERN.match(path.name)
        if not match:
            continue
        with path.open(encoding="utf-8") as result_file:
            metrics = json.load(result_file).get("metrics", {})
        grouped[(match.group(1), match.group(2))].append({
            "rps": metric_value(metrics, "external_info_requests", "rate"),
            "success": metric_value(metrics, "external_info_success", "rate") * 100,
            "error": metric_value(metrics, "external_info_error", "rate") * 100,
            "p50": metric_value(metrics, "external_info_duration_ms", "med"),
            "p95": metric_value(metrics, "external_info_duration_ms", "p(95)"),
            "p99": metric_value(metrics, "external_info_duration_ms", "p(99)"),
            "live": metric_value(metrics, "external_source_live", "count"),
            "cache": metric_value(metrics, "external_source_cache", "count"),
            "product_calls": metric_value(metrics, "product_external_calls", "value", -1),
            "delivery_calls": metric_value(metrics, "delivery_external_calls", "value", -1),
        })
    return grouped


def median(rows, key):
    return statistics.median(row[key] for row in rows)


def render(grouped):
    lines = [
        "# 캐시 Fallback 반복 측정 요약",
        "",
        "각 값은 같은 Variant와 Scenario로 실행한 결과의 중앙값입니다.",
        "",
        "| Variant | Scenario | Runs | RPS | 성공률 | 오류율 | p50(ms) | p95(ms) | p99(ms) | LIVE | CACHE | 상품 호출 | 배송 호출 |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for (variant, scenario), rows in sorted(grouped.items()):
        lines.append(
            f"| {variant} | {scenario} | {len(rows)} "
            f"| {median(rows, 'rps'):.2f} "
            f"| {median(rows, 'success'):.2f}% "
            f"| {median(rows, 'error'):.2f}% "
            f"| {median(rows, 'p50'):.2f} "
            f"| {median(rows, 'p95'):.2f} "
            f"| {median(rows, 'p99'):.2f} "
            f"| {median(rows, 'live'):.0f} "
            f"| {median(rows, 'cache'):.0f} "
            f"| {median(rows, 'product_calls'):.0f} "
            f"| {median(rows, 'delivery_calls'):.0f} |"
        )
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("result_dir", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    content = render(load_results(args.result_dir))
    if args.output:
        args.output.write_text(content, encoding="utf-8")
    else:
        print(content, end="")


if __name__ == "__main__":
    main()
