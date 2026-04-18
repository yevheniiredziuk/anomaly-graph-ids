#!/usr/bin/env python3
"""Figure 6.3 — F1 heatmap over (w1, w2) weight simplex (w3 = 1 − w1 − w2)."""
from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd

plt.rcParams.update(
    {"font.family": "DejaVu Serif", "font.size": 10, "figure.dpi": 150}
)


def plot_simplex(df: pd.DataFrame, out_png: Path, out_svg: Path) -> None:
    best_theta = df.groupby("thetaA")["f1"].max().idxmax()
    sub = df[df["thetaA"] == best_theta]

    fig, ax = plt.subplots(figsize=(5.5, 4.5))

    sc = ax.scatter(
        sub["w1"],
        sub["w2"],
        c=sub["f1"],
        s=200,
        cmap="gray_r",
        edgecolors="black",
        linewidths=0.5,
        vmin=0,
        vmax=max(0.1, sub["f1"].max() * 1.1),
    )

    for _, row in sub.iterrows():
        ax.annotate(
            f"{row['f1']:.3f}",
            (row["w1"], row["w2"]),
            ha="center",
            va="center",
            fontsize=6,
            color="white" if row["f1"] > sub["f1"].max() * 0.5 else "black",
        )

    best = sub.loc[sub["f1"].idxmax()]
    ax.annotate(
        "★ best",
        (best["w1"], best["w2"]),
        xytext=(best["w1"] + 0.08, best["w2"] + 0.08),
        fontsize=9,
        arrowprops=dict(arrowstyle="->", lw=1, color="black"),
    )

    ax.set_xlim(-0.02, 0.82)
    ax.set_ylim(-0.02, 0.82)
    ax.set_xlabel(r"$w_1$ (BC z-score)")
    ax.set_ylabel(r"$w_2$ (community change)")
    ax.set_title(
        f"Figure 6.3 — F1 over weight simplex (θ_A = {best_theta:.2f})",
        fontsize=11,
    )
    plt.colorbar(sc, ax=ax, label="F1")
    ax.text(
        0.4, 0.75, r"$w_3 = 1 - w_1 - w_2$", fontsize=9, style="italic", color="gray"
    )
    ax.grid(True, alpha=0.3, linestyle=":", linewidth=0.5)

    fig.tight_layout()
    out_png.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_png, dpi=300, bbox_inches="tight")
    fig.savefig(out_svg, bbox_inches="tight")
    print(f"[done] {out_png} + {out_svg}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=Path("results/weight_simplex.csv"))
    parser.add_argument(
        "--out-png", type=Path, default=Path("results/figures/fig_6_3_weight_simplex.png")
    )
    parser.add_argument(
        "--out-svg", type=Path, default=Path("results/figures/fig_6_3_weight_simplex.svg")
    )
    args = parser.parse_args()

    df = pd.read_csv(args.input)
    plot_simplex(df, args.out_png, args.out_svg)


if __name__ == "__main__":
    main()
