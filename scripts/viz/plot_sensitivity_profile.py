#!/usr/bin/env python3
"""
Plot F1 as function of theta_A for each weight simplex corner / edge / centre.
Shows sensitivity of method to single-component weighting and reveals
whether the composite metric adds anything over pure-alpha_1.
"""
import argparse
from pathlib import Path
import matplotlib.pyplot as plt
import pandas as pd

plt.rcParams.update({"font.family": "serif", "font.size": 10, "figure.dpi": 150})


def _label(r):
    return f"w=({r['w1']:.2f}, {r['w2']:.2f}, {r['w3']:.2f})"


def plot(df: pd.DataFrame, out_png: Path, out_svg: Path) -> None:
    df = df.copy()
    df["config"] = df.apply(_label, axis=1)

    linestyles = ["-", "--", "-.", ":"]
    markers = ["o", "s", "^", "D", "v", "*", "x", "+"]

    fig, ax = plt.subplots(figsize=(6.8, 4.0))

    configs = sorted(df["config"].unique())
    for i, cfg in enumerate(configs):
        sub = df[df["config"] == cfg].sort_values("thetaA")
        ax.plot(
            sub["thetaA"],
            sub["f1"],
            label=cfg,
            linestyle=linestyles[i % len(linestyles)],
            marker=markers[i % len(markers)],
            markersize=3,
            linewidth=1.0,
            color="black",
        )

    ax.set_xlabel(r"$\theta_A$")
    ax.set_ylabel("F1")
    ax.set_ylim(0, max(df["f1"].max() * 1.15, 0.02))
    ax.grid(True, alpha=0.3, linestyle=":", linewidth=0.5)
    ax.legend(loc="upper right", fontsize=7, ncol=2, framealpha=0.9)
    ax.set_title(
        r"F1 sensitivity to $\theta_A$ across weight configurations (fine grid)",
        fontsize=10,
    )

    fig.tight_layout()
    out_png.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_png, dpi=300, bbox_inches="tight")
    fig.savefig(out_svg, bbox_inches="tight")
    print(f"[done] {out_png} + {out_svg}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input", type=Path, default=Path("results/fine_grid_results.csv")
    )
    parser.add_argument(
        "--out-png",
        type=Path,
        default=Path("results/figures/fig_6_3b_sensitivity.png"),
    )
    parser.add_argument(
        "--out-svg",
        type=Path,
        default=Path("results/figures/fig_6_3b_sensitivity.svg"),
    )
    args = parser.parse_args()
    df = pd.read_csv(args.input)
    plot(df, args.out_png, args.out_svg)


if __name__ == "__main__":
    main()
