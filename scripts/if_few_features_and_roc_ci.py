"""
Контроль «feature count vs feature nature» + bootstrap CI для ROC-AUC.

Закриває критичні зауваження З.2 і З.4 рецензії v13:

  З.2  «IF на 21 ознаці vs IF на 3 графових — конфаундить кількість ознак з
        їх природою (потокові vs графові). Додати контроль IF на 3-5
        потокових ознаках для ізоляції ефекту "кількість" від "природа".»

  З.4  «ROC-AUC у Таблиці 3 наведено як точкові оцінки без CI попри явне
        твердження про потенційну варіабельність — внутрішньо суперечно.
        Додати bootstrap CI або прибрати ROC-AUC.»

Реалізація:
  - Запозичає load_flows + universe-building + per_day_split + bootstrap_ci з
    if_baseline_dual_gt.py, не дублюючи код.
  - Виконує IF на трьох ознакових просторах: 3 ознаки {flow_count,
    bytes_total, unique_remote_ports}; 5 ознак (додаємо flow_duration_mean,
    log_flow_count); для довідки — той самий 21-ознаковий IF для крос-
    верифікації проти dual_gt_results.json.
  - Обчислює ROC-AUC з 1000-resamples стратифікованим bootstrap-CI для всіх
    конфігурацій IF (включно з 3/5-ознаковими); rule-based ROC-AUC
    обчислюється на бінарних прогнозах (відповідає табл. 3, де він
    наведений як 0,734).

Вихід:
  results/few_features_and_roc_ci.json
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest
from sklearn.metrics import f1_score, roc_auc_score

# Імпортуємо інфраструктуру з основного скрипта
SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
from if_baseline_dual_gt import (  # noqa: E402
    load_flows, build_universe_and_gt, build_features, per_day_split,
    attach_gt, metrics, run_rules, evaluate_rules,
    FEATURE_NAMES, IF_PARAMS, RNG_SEED, N_BOOTSTRAP, RESULTS,
)

# Ознакові простори для контролю
FEATURES_3 = ["flow_count", "bytes_fwd_sum", "unique_remote_ports"]
FEATURES_5 = FEATURES_3 + ["flow_duration_mean", "log_flow_count"]


def bootstrap_ci_scores(
    y_true: np.ndarray,
    scores: np.ndarray,
    y_pred: np.ndarray,
    n_resamples: int = N_BOOTSTRAP,
    seed: int = RNG_SEED,
) -> dict:
    """Стратифікований bootstrap 95% CI для F1, ROC-AUC і базової метрики на
    бінарних прогнозах + неперервних балах.
    """
    rng = np.random.default_rng(seed)
    pos_idx = np.where(y_true == 1)[0]
    neg_idx = np.where(y_true == 0)[0]
    if len(pos_idx) == 0 or len(neg_idx) == 0:
        return {"F1": (0.0, 0.0), "ROC_AUC": (0.0, 0.0)}

    f1s = np.empty(n_resamples)
    aucs = np.empty(n_resamples)
    for i in range(n_resamples):
        pos_sample = rng.choice(pos_idx, size=len(pos_idx), replace=True)
        neg_sample = rng.choice(neg_idx, size=len(neg_idx), replace=True)
        idx = np.concatenate([pos_sample, neg_sample])
        y_t = y_true[idx]
        f1s[i] = f1_score(y_t, y_pred[idx], zero_division=0)
        try:
            aucs[i] = roc_auc_score(y_t, scores[idx])
        except ValueError:
            aucs[i] = 0.5

    def ci(arr: np.ndarray) -> tuple[float, float]:
        return float(np.quantile(arr, 0.025)), float(np.quantile(arr, 0.975))

    return {"F1": ci(f1s), "ROC_AUC": ci(aucs)}


def run_if_with_roc(train: pd.DataFrame, test: pd.DataFrame, features: list[str],
                    label: str) -> dict:
    """IF на заданому підмножині ознак з ROC-AUC і bootstrap CI."""
    X_train = train[features].to_numpy(dtype=np.float64)
    X_test = test[features].to_numpy(dtype=np.float64)
    y_train = train["y"].to_numpy()
    y_test = test["y"].to_numpy()

    X_train = np.nan_to_num(X_train, nan=0.0, posinf=0.0, neginf=0.0)
    X_test = np.nan_to_num(X_test, nan=0.0, posinf=0.0, neginf=0.0)

    clf = IsolationForest(**IF_PARAMS)
    clf.fit(X_train)
    train_anom = -clf.score_samples(X_train)
    test_anom = -clf.score_samples(X_test)

    candidates = np.quantile(train_anom, np.linspace(0.5, 0.999, 200))
    best_thr, best_f1 = float("-inf"), -1.0
    for thr in candidates:
        pred_t = (train_anom > thr).astype(int)
        f1_t = f1_score(y_train, pred_t, zero_division=0)
        if f1_t > best_f1:
            best_f1, best_thr = f1_t, float(thr)

    pred_test = (test_anom > best_thr).astype(int)
    m = metrics(y_test, pred_test)
    try:
        m["ROC_AUC"] = float(roc_auc_score(y_test, test_anom))
    except ValueError:
        m["ROC_AUC"] = float("nan")
    m["threshold"] = best_thr
    m["train_F1_at_threshold"] = float(best_f1)
    m["feature_count"] = len(features)
    m["features"] = features
    m["label"] = label
    m["bootstrap_95ci"] = bootstrap_ci_scores(y_test, test_anom, pred_test)
    return m


def main() -> None:
    print("=" * 70)
    print("CONTROL EXPERIMENT: IF на 3/5/21 потокових ознаках + ROC-AUC з CI")
    print("=" * 70)

    df = load_flows()
    sets = build_universe_and_gt(df)

    feats_source = build_features(df, sets["source"]["universe"], source_only=True)
    feats_source = attach_gt(feats_source, sets["source"]["gt_set"])
    train_s, test_s = per_day_split(feats_source)

    feats_victim = build_features(df, sets["victim"]["universe"], source_only=False)
    feats_victim = attach_gt(feats_victim, sets["victim"]["gt_set"])
    train_v, test_v = per_day_split(feats_victim)

    print(f"\nsource-only split: train={len(train_s)} (n+={train_s['y'].sum()}), "
          f"test={len(test_s)} (n+={test_s['y'].sum()})")
    print(f"victim-tagging split: train={len(train_v)} (n+={train_v['y'].sum()}), "
          f"test={len(test_v)} (n+={test_v['y'].sum()})")

    runs = []

    # source-only (відповідає протоколу IF у v13 ARTICLE.md)
    for feats, label in [
        (FEATURES_3, "IF_3_flow_features_source_only"),
        (FEATURES_5, "IF_5_flow_features_source_only"),
        (FEATURE_NAMES, "IF_21_flow_features_source_only"),
    ]:
        print(f"\n--- {label} ({len(feats)} features) ---")
        res = run_if_with_roc(train_s, test_s, feats, label)
        # Прибираємо великі масиви перед серіалізацією
        runs.append({k: v for k, v in res.items() if not k.startswith("_")})
        print(f"  F1 = {res['F1']:.4f}  [95% CI: {res['bootstrap_95ci']['F1'][0]:.4f}; "
              f"{res['bootstrap_95ci']['F1'][1]:.4f}]")
        print(f"  ROC-AUC = {res['ROC_AUC']:.4f}  [95% CI: "
              f"{res['bootstrap_95ci']['ROC_AUC'][0]:.4f}; "
              f"{res['bootstrap_95ci']['ROC_AUC'][1]:.4f}]")
        print(f"  P = {res['P']:.4f}, R = {res['R']:.4f}, TP={res['TP']}, FP={res['FP']}, "
              f"FN={res['FN']}, TN={res['TN']}")

    # Для довідки: victim-tagging - 21 ознака (для звірки з табл. 8)
    print(f"\n--- IF_21_flow_features_victim_tagging (control) ---")
    res = run_if_with_roc(train_v, test_v, FEATURE_NAMES, "IF_21_flow_features_victim_tagging")
    runs.append({k: v for k, v in res.items() if not k.startswith("_")})
    print(f"  F1 = {res['F1']:.4f}  [95% CI: {res['bootstrap_95ci']['F1'][0]:.4f}; "
          f"{res['bootstrap_95ci']['F1'][1]:.4f}]")
    print(f"  ROC-AUC = {res['ROC_AUC']:.4f}  [95% CI: "
          f"{res['bootstrap_95ci']['ROC_AUC'][0]:.4f}; "
          f"{res['bootstrap_95ci']['ROC_AUC'][1]:.4f}]")

    # ---- ROC-AUC для rule-based з bootstrap CI ----
    print("\n--- ROC-AUC для rule-based з bootstrap CI ---")
    rule_preds = run_rules(df)

    print("\n  Rule-based на victim-tagging GT (повний universe):")
    rule_v = evaluate_rules(rule_preds, sets["victim"]["universe"], sets["victim"]["gt_set"],
                             with_bootstrap=False)
    y_v = rule_v["_y_true"]
    pred_v = rule_v["_y_pred"]
    auc_v = float(roc_auc_score(y_v, pred_v.astype(float))) if y_v.sum() > 0 else float("nan")
    ci_v = bootstrap_ci_scores(y_v, pred_v.astype(float), pred_v)
    rule_v_out = {k: v for k, v in rule_v.items() if not k.startswith("_")}
    rule_v_out["ROC_AUC"] = auc_v
    rule_v_out["bootstrap_95ci"] = ci_v
    rule_v_out["label"] = "rule_based_victim_tagging"
    runs.append(rule_v_out)
    print(f"  F1 = {rule_v['F1']:.4f}, ROC-AUC = {auc_v:.4f}  "
          f"[95% CI: {ci_v['ROC_AUC'][0]:.4f}; {ci_v['ROC_AUC'][1]:.4f}]")

    print("\n  Rule-based на source-only GT (повний universe):")
    rule_s = evaluate_rules(rule_preds, sets["source"]["universe"], sets["source"]["gt_set"],
                             with_bootstrap=False)
    y_s = rule_s["_y_true"]
    pred_s = rule_s["_y_pred"]
    auc_s = float(roc_auc_score(y_s, pred_s.astype(float))) if y_s.sum() > 0 else float("nan")
    ci_s = bootstrap_ci_scores(y_s, pred_s.astype(float), pred_s)
    rule_s_out = {k: v for k, v in rule_s.items() if not k.startswith("_")}
    rule_s_out["ROC_AUC"] = auc_s
    rule_s_out["bootstrap_95ci"] = ci_s
    rule_s_out["label"] = "rule_based_source_only"
    runs.append(rule_s_out)
    print(f"  F1 = {rule_s['F1']:.4f}, ROC-AUC = {auc_s:.4f}  "
          f"[95% CI: {ci_s['ROC_AUC'][0]:.4f}; {ci_s['ROC_AUC'][1]:.4f}]")

    # ---- Збереження ----
    out = {
        "config": {
            "rng_seed": RNG_SEED,
            "n_bootstrap": N_BOOTSTRAP,
            "if_params": dict(IF_PARAMS),
            "features_3": FEATURES_3,
            "features_5": FEATURES_5,
            "features_21": FEATURE_NAMES,
            "purpose": (
                "Контроль 'feature count vs feature nature' (Z.2): IF на 3/5 "
                "потокових ознаках для ізоляції ефекту кількості ознак від "
                "ефекту їх природи. Bootstrap CI для ROC-AUC (Z.4)."
            ),
        },
        "runs": runs,
    }
    out_path = RESULTS / "few_features_and_roc_ci.json"
    out_path.write_text(json.dumps(out, indent=2, ensure_ascii=False))
    print(f"\n[saved] {out_path}")


if __name__ == "__main__":
    main()
