#!/usr/bin/env python3
"""Turn a JMH JSON result file into Markdown tables for docs/BENCHMARKS.md.

Usage: jmh_to_md.py results.json [environment.txt] > report.md

One table per benchmark class, mode and parameter set. Every row shows the score with its 99.9%
error, allocation per operation when `-prof gc` was used, the percentiles for SampleTime runs,
and the ratio to the best row of the same table. Nothing is rounded away: if two rows overlap
within their errors the report says so instead of declaring a winner.
"""
import json
import sys
from collections import defaultdict

HIGHER_IS_BETTER = {"thrpt"}


def fmt(x):
    if x != x:  # NaN
        return "n/a"
    if abs(x) >= 1000:
        return f"{x:,.0f}"
    if abs(x) >= 10:
        return f"{x:.1f}"
    return f"{x:.3f}"


def main():
    results = json.load(open(sys.argv[1]))
    env = open(sys.argv[2]).read().strip() if len(sys.argv) > 2 else ""

    groups = defaultdict(list)
    for r in results:
        cls, method = r["benchmark"].rsplit(".", 2)[-2:]
        params = ", ".join(f"{k}={v}" for k, v in sorted((r.get("params") or {}).items()))
        groups[(cls, r["mode"], params, r["threads"])].append((method, r))

    out = ["# Benchmark results", ""]
    if env:
        out += ["## Environment", "", "```", env, "```", ""]
    out += [
        "Scores are per operation. `±` is the 99.9% confidence half-interval over all forks and",
        "iterations. `ratio` is the row divided by the best row of its table (1.00 = best).",
        "Rows whose intervals overlap the best row are marked `≈` (no measured difference).",
        "",
    ]

    for (cls, mode, params, threads), rows in sorted(groups.items()):
        unit = rows[0][1]["primaryMetric"]["scoreUnit"]
        higher = mode in HIGHER_IS_BETTER
        best = max if higher else min
        best_row = best(rows, key=lambda m: m[1]["primaryMetric"]["score"])[1]["primaryMetric"]
        title = f"## {cls} — {mode}, {threads} thread(s)"
        if params:
            title += f" — {params}"
        out += [title, "", f"Unit: `{unit}` ({'higher' if higher else 'lower'} is better)", ""]
        sample = mode == "sample"
        head = "| implementation | score | ± | ratio |"
        sep = "|---|---:|---:|---:|"
        if sample:
            head += " p50 | p99 | p99.9 |"
            sep += "---:|---:|---:|"
        head += " alloc B/op |"
        sep += "---:|"
        out += [head, sep]
        for method, r in sorted(rows, key=lambda m: m[1]["primaryMetric"]["score"], reverse=higher):
            pm = r["primaryMetric"]
            score, err = pm["score"], pm["scoreError"]
            ratio = score / best_row["score"]
            overlap = abs(score - best_row["score"]) <= (err + best_row["scoreError"])
            mark = " ≈" if overlap and pm is not best_row else ""
            line = f"| {method} | {fmt(score)} | {fmt(err)} | {ratio:.2f}{mark} |"
            if sample:
                pc = pm.get("scorePercentiles", {})
                line += " " + " | ".join(fmt(pc.get(k, float("nan"))) for k in ("50.0", "99.0", "99.9")) + " |"
            alloc = r.get("secondaryMetrics", {}).get("·gc.alloc.rate.norm", {}).get("score")
            line += f" {fmt(alloc) if alloc is not None else 'n/a'} |"
            out.append(line)
        out.append("")
    print("\n".join(out))


if __name__ == "__main__":
    main()
