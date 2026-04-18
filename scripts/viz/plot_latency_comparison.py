#!/usr/bin/env python3
"""Figure 6.5 — Neo4j vs PostgreSQL window-query latency (JMH results)."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

plt.rcParams.update(
    {"font.family": "DejaVu Serif", "font.size": 10, "figure.dpi": 150}
)


def parse_jmh_json(path: Path) -> list[dict]:
    with open(path) as f:
        data = json.load(f)

    result = []
    for entry in data:
        full_name = entry["benchmark"]
        parts = full_name.split(".")
        class_name = parts[-2].replace("Benchmark", "")
        method_name = parts[-1]
        db = "Neo4j" if "neo4j" in full_name.lower() else "PostgreSQL"
        pcts = entry["primaryMetric"]["scorePercentiles"]
        result.append(
            {
                "db": db,
                "class": class_name,
                "method": method_name,
                "label": f"{db}:{method_name}",
                "p50": pcts.get("50.0", 0),
                "p95": pcts.get("95.0", 0),
                "p99": pcts.get("99.0", 0),
            }
        )
    result.sort(key=lambda r: (r["db"], r["class"]))
    return result


def plot_latency(entries: list[dict], out_png: Path, out_svg: Path) -> None:
    labels = [e["label"] for e in entries]
    p50 = [e["p50"] for e in entries]
    p95 = [e["p95"] for e in entries]
    p99 = [e["p99"] for e in entries]

    x = np.arange(len(labels))
    w = 0.28

    fig, ax = plt.subplots(figsize=(9.0, 4.0))
    ax.bar(x - w, p50, w, color="#606060", edgecolor="black", linewidth=0.5, label="p50")
    ax.bar(
        x, p95, w, color="#A0A0A0", edgecolor="black", linewidth=0.5, label="p95", hatch="//"
    )
    ax.bar(
        x + w, p99, w, color="#D0D0D0", edgecolor="black", linewidth=0.5, label="p99", hatch="xx"
    )

    ax.set_xticks(x)
    ax.set_xticklabels(labels, rotation=30, ha="right", fontsize=8)
    ax.set_ylabel("Latency (ms)")
    ax.set_yscale("log")
    ax.grid(True, alpha=0.3, linestyle=":", linewidth=0.5, axis="y", which="both")
    ax.legend(loc="upper left")
    ax.set_title(
        "Figure 6.5 — Window-query latency: Neo4j vs PostgreSQL", fontsize=11
    )

    fig.tight_layout()
    out_png.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_png, dpi=300, bbox_inches="tight")
    fig.savefig(out_svg, bbox_inches="tight")
    print(f"[done] {out_png} + {out_svg}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument(
        "--out-png", type=Path, default=Path("results/figures/fig_6_5_latency.png")
    )
    parser.add_argument(
        "--out-svg", type=Path, default=Path("results/figures/fig_6_5_latency.svg")
    )
    args = parser.parse_args()

    entries = parse_jmh_json(args.input)
    plot_latency(entries, args.out_png, args.out_svg)


if __name__ == "__main__":
    main()
