#!/usr/bin/env python3
"""Figure 6.4 — Per-attack-type recall: graph vs baseline."""
from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

plt.rcParams.update(
    {"font.family": "DejaVu Serif", "font.size": 10, "figure.dpi": 150}
)


def plot_recall(df: pd.DataFrame, out_png: Path, out_svg: Path) -> None:
    df = df.sort_values("graph_recall", ascending=False).reset_index(drop=True)
    x = np.arange(len(df))
    w = 0.38

    fig, ax = plt.subplots(figsize=(7.0, 3.5))
    ax.bar(
        x - w / 2,
        df["graph_recall"],
        w,
        color="#404040",
        edgecolor="black",
        linewidth=0.5,
        label="graph method",
    )
    ax.bar(
        x + w / 2,
        df["baseline_recall"],
        w,
        color="#B0B0B0",
        edgecolor="black",
        linewidth=0.5,
        hatch="//",
        label="baseline (rule-based)",
    )

    ax.set_xticks(x)
    ax.set_xticklabels(df["attack_type"], rotation=25, ha="right", fontsize=9)
    ax.set_ylabel("Recall")
    ax.set_ylim(0, 1.02)
    ax.grid(True, alpha=0.3, linestyle=":", linewidth=0.5, axis="y")
    ax.legend(loc="upper right")

    for rect in ax.containers:
        ax.bar_label(rect, fmt="%.2f", padding=2, fontsize=7)

    ax.set_title("Figure 6.4 — Per-attack-type recall", fontsize=11)
    fig.tight_layout()
    out_png.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_png, dpi=300, bbox_inches="tight")
    fig.savefig(out_svg, bbox_inches="tight")
    print(f"[done] {out_png} + {out_svg}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input", type=Path, default=Path("results/per_attack_recall.csv")
    )
    parser.add_argument(
        "--out-png",
        type=Path,
        default=Path("results/figures/fig_6_4_per_attack_recall.png"),
    )
    parser.add_argument(
        "--out-svg",
        type=Path,
        default=Path("results/figures/fig_6_4_per_attack_recall.svg"),
    )
    args = parser.parse_args()

    df = pd.read_csv(args.input)
    plot_recall(df, args.out_png, args.out_svg)


if __name__ == "__main__":
    main()
