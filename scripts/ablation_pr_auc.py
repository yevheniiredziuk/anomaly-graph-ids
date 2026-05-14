"""
PR-AUC для 8 abации-конфігурацій (Таблиця 4 ablation).

Закриває зауваження З.7 рецензії v13: ablation у v13 використовує тільки F1,
тоді як основна Таблиця 3 — PR-AUC. Рецензент справедливо вимагає
консистентності — або додати PR-AUC до ablation, або пояснити асиметрію.
Цей скрипт додає PR-AUC до Таблиці 4.

Вхід:
  results/alphas_with_gt.csv (76 647 (host, window) з alpha1_norm, alpha2,
  alpha3, is_attack_any).

Вихід:
  results/ablation_pr_auc.json
"""

import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import (average_precision_score, f1_score, precision_score,
                              recall_score)

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results"
ALPHAS = RESULTS / "alphas_with_gt.csv"

# 8 ablation-конфігурацій з Таблиці 4 v13
CONFIGS = [
    {"label": "Full (coarse-grid-best)", "w": (0.5, 0.4, 0.1), "theta_A": 0.600},
    {"label": "Pure α1", "w": (1.0, 0.0, 0.0), "theta_A": 0.225},
    {"label": "Pure α2", "w": (0.0, 1.0, 0.0), "theta_A": 0.230},
    {"label": "Pure α3", "w": (0.0, 0.0, 1.0), "theta_A": 0.050},
    {"label": "Without α1", "w": (0.0, 0.5, 0.5), "theta_A": 0.500},
    {"label": "Without α2", "w": (0.5, 0.0, 0.5), "theta_A": 0.630},
    {"label": "Without α3", "w": (0.5, 0.5, 0.0), "theta_A": 0.600},
    {"label": "Simplex centroid", "w": (1/3, 1/3, 1/3), "theta_A": 0.950},
]


def main() -> None:
    print("=" * 70)
    print("PR-AUC ABLATION (Таблиця 4)")
    print("=" * 70)

    df = pd.read_csv(ALPHAS)
    y = df["is_attack_any"].astype(int).to_numpy()
    a1 = df["alpha1_norm"].fillna(0).to_numpy()
    a2 = df["alpha2"].fillna(0).to_numpy()
    a3 = df["alpha3"].fillna(0).to_numpy()
    print(f"Loaded {len(df)} rows, n+ = {y.sum()} ({y.mean()*100:.3f}%)")

    results = []
    for cfg in CONFIGS:
        w1, w2, w3 = cfg["w"]
        thr = cfg["theta_A"]
        score = w1 * a1 + w2 * a2 + w3 * a3
        pred = (score > thr).astype(int)
        f1 = f1_score(y, pred, zero_division=0)
        prec = precision_score(y, pred, zero_division=0)
        rec = recall_score(y, pred, zero_division=0)
        pr_auc = average_precision_score(y, score)
        out = {
            "label": cfg["label"],
            "weights": list(cfg["w"]),
            "theta_A": thr,
            "F1": float(f1),
            "P": float(prec),
            "R": float(rec),
            "PR_AUC": float(pr_auc),
        }
        results.append(out)
        print(f"{cfg['label']:30s}  w={cfg['w']}  θ={thr:.3f}  F1={f1:.4f}  "
              f"PR-AUC={pr_auc:.4f}")

    out_path = RESULTS / "ablation_pr_auc.json"
    out_path.write_text(json.dumps({"base_rate": float(y.mean()), "configs": results},
                                    indent=2, ensure_ascii=False))
    print(f"\n[saved] {out_path}")


if __name__ == "__main__":
    main()
