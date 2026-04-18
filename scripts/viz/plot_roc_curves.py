#!/usr/bin/env python3
"""Figure 6.1 — ROC curves per attack type (graph vs baseline)."""
from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd

plt.rcParams.update(
    {
        "font.family": "DejaVu Serif",
        "font.size": 9,
        "axes.labelsize": 10,
        "axes.titlesize": 10,
        "legend.fontsize": 8,
        "figure.dpi": 150,
    }
)


def plot_roc(df: pd.DataFrame, out_png: Path, out_svg: Path) -> None:
    attack_types = [a for a in sorted(df["attack_type"].unique()) if a != "ALL"]
    if not attack_types:
        raise ValueError("No attack types in ROC data — check export step")

    ncols = 3 if len(attack_types) > 4 else 2
    nrows = (len(attack_types) + ncols - 1) // ncols
    fig, axes = plt.subplots(
        nrows, ncols, figsize=(3.0 * ncols, 2.8 * nrows), squeeze=False
    )

    for idx, attack in enumerate(attack_types):
        ax = axes[idx // ncols][idx % ncols]

        graph = df[(df["method"] == "graph") & (df["attack_type"] == attack)].sort_values(
            "threshold", ascending=False
        )
        if not graph.empty:
            ax.plot(
                graph["fpr"],
                graph["tpr"],
                marker="o",
                markersize=3,
                linewidth=1.3,
                color="black",
                linestyle="-",
                label="graph method",
            )

        baseline = df[(df["method"] == "baseline") & (df["attack_type"] == attack)]
        if not baseline.empty:
            ax.scatter(
                baseline["fpr"],
                baseline["tpr"],
                marker="*",
                s=120,
                facecolors="none",
                edgecolors="black",
                linewidth=1.2,
                label="baseline (rule-based)",
            )

        ax.plot([0, 1], [0, 1], linestyle=":", color="gray", linewidth=0.8)
        ax.set_xlim(0, 1)
        ax.set_ylim(0, 1.02)
        ax.set_xlabel("False Positive Rate")
        ax.set_ylabel("True Positive Rate")
        ax.set_title(attack, fontsize=9)
        ax.grid(True, alpha=0.3, linestyle=":", linewidth=0.5)
        ax.legend(loc="lower right", framealpha=0.9)

    for idx in range(len(attack_types), nrows * ncols):
        axes[idx // ncols][idx % ncols].set_visible(False)

    fig.suptitle("Figure 6.1 — ROC curves per attack type", fontsize=11, y=1.01)
    fig.tight_layout()
    out_png.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_png, dpi=300, bbox_inches="tight")
    fig.savefig(out_svg, bbox_inches="tight")
    print(f"[done] {out_png} + {out_svg}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=Path("results/roc_data.csv"))
    parser.add_argument(
        "--out-png", type=Path, default=Path("results/figures/fig_6_1_roc_curves.png")
    )
    parser.add_argument(
        "--out-svg", type=Path, default=Path("results/figures/fig_6_1_roc_curves.svg")
    )
    args = parser.parse_args()

    df = pd.read_csv(args.input)
    plot_roc(df, args.out_png, args.out_svg)


if __name__ == "__main__":
    main()
