#!/usr/bin/env python3
"""Figure 6.2 — F1 vs analytical window size Δt."""
from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd

plt.rcParams.update(
    {"font.family": "DejaVu Serif", "font.size": 10, "figure.dpi": 150}
)


def plot_window_sweep(df: pd.DataFrame, out_png: Path, out_svg: Path) -> None:
    fig, ax = plt.subplots(figsize=(5.5, 3.5))

    for method, style in [
        ("graph", {"marker": "o", "linestyle": "-", "color": "black"}),
        ("baseline", {"marker": "s", "linestyle": "--", "color": "black"}),
    ]:
        sub = df[df["method"] == method].sort_values("window_size_minutes")
        if sub.empty:
            continue
        ax.plot(
            sub["window_size_minutes"],
            sub["f1"],
            label=f"{method} method",
            markersize=5,
            linewidth=1.5,
            markerfacecolor="none",
            **style,
        )

    ax.set_xlabel("Analytical window size Δt (min)")
    ax.set_ylabel("F1")
    ax.set_ylim(0, 1.02)
    ax.grid(True, alpha=0.3, linestyle=":", linewidth=0.5)
    ax.legend(loc="lower center")
    ax.set_title("Figure 6.2 — F1 vs window size", fontsize=11)

    fig.tight_layout()
    out_png.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_png, dpi=300, bbox_inches="tight")
    fig.savefig(out_svg, bbox_inches="tight")
    print(f"[done] {out_png} + {out_svg}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input", type=Path, default=Path("results/window_size_sweep.csv")
    )
    parser.add_argument(
        "--out-png", type=Path, default=Path("results/figures/fig_6_2_window_size.png")
    )
    parser.add_argument(
        "--out-svg", type=Path, default=Path("results/figures/fig_6_2_window_size.svg")
    )
    args = parser.parse_args()

    df = pd.read_csv(args.input)
    plot_window_sweep(df, args.out_png, args.out_svg)


if __name__ == "__main__":
    main()
