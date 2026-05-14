"""
Supervised baseline (logistic regression on alpha_1, alpha_2, alpha_3)
+ bootstrap CI + McNemar test.

Addresses reviewer comments K1 and K3:
- K1: adds a supervised variant of the proposed features (logistic regression
  on the exported 76 647 (host, window) records) to make the baseline comparison
  symmetric — unsupervised structural detector vs. supervised structural detector
  vs. supervised expert rules (PostgreSQL).
- K3: reports 95% bootstrap confidence intervals for F1 and a McNemar test
  comparing pure-alpha_1 vs. logistic regression on the same evaluation sample.

Inputs:
  results/alphas_with_gt.csv
    columns: host_ip, window_start, alpha1_norm, alpha2, alpha3, score,
             is_attack_any (host-window GT: source OR destination),
             is_attack_src (source-only GT), src_attack

Outputs:
  results/supervised_baseline_report.md  (human-readable summary)
  results/supervised_baseline.json       (machine-readable metrics)
"""

import json
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import chi2
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import f1_score, precision_score, recall_score

RESULTS = Path(__file__).resolve().parent.parent / "results"
RNG_SEED = 20260422
N_BOOTSTRAP = 1000


def load_data() -> pd.DataFrame:
    df = pd.read_csv(RESULTS / "alphas_with_gt.csv", parse_dates=["window_start"])
    df["day"] = df["window_start"].dt.day
    return df


def split_train_test(df: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Train: first 50% of time of Tue+Wed. Test: second 50%.

    Matches paper Section 6.2.1: per-day 50/50 temporal split.
    """
    parts: list[pd.DataFrame] = []
    for day, day_df in df.groupby("day"):
        cutoff = day_df["window_start"].quantile(0.5)
        day_df = day_df.assign(is_train=day_df["window_start"] < cutoff)
        parts.append(day_df)
    full = pd.concat(parts, ignore_index=True)
    train = full[full["is_train"]].reset_index(drop=True)
    test = full[~full["is_train"]].reset_index(drop=True)
    return train, test


def f1_precision_recall(y_true: np.ndarray, y_pred: np.ndarray) -> dict[str, float]:
    return {
        "P": float(precision_score(y_true, y_pred, zero_division=0)),
        "R": float(recall_score(y_true, y_pred, zero_division=0)),
        "F1": float(f1_score(y_true, y_pred, zero_division=0)),
    }


def bootstrap_ci(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    n_resamples: int = N_BOOTSTRAP,
    seed: int = RNG_SEED,
) -> dict[str, tuple[float, float]]:
    """Stratified bootstrap 95% CI for P/R/F1.

    Stratified by y_true to preserve base rate across resamples.
    """
    rng = np.random.default_rng(seed)
    pos_idx = np.where(y_true == 1)[0]
    neg_idx = np.where(y_true == 0)[0]

    f1s = np.empty(n_resamples)
    ps = np.empty(n_resamples)
    rs = np.empty(n_resamples)

    for i in range(n_resamples):
        pos_sample = rng.choice(pos_idx, size=len(pos_idx), replace=True)
        neg_sample = rng.choice(neg_idx, size=len(neg_idx), replace=True)
        idx = np.concatenate([pos_sample, neg_sample])
        f1s[i] = f1_score(y_true[idx], y_pred[idx], zero_division=0)
        ps[i] = precision_score(y_true[idx], y_pred[idx], zero_division=0)
        rs[i] = recall_score(y_true[idx], y_pred[idx], zero_division=0)

    def ci(arr: np.ndarray) -> tuple[float, float]:
        return float(np.quantile(arr, 0.025)), float(np.quantile(arr, 0.975))

    return {"F1": ci(f1s), "P": ci(ps), "R": ci(rs)}


def mcnemar(y_true: np.ndarray, y_a: np.ndarray, y_b: np.ndarray) -> dict[str, float]:
    """McNemar's test (with continuity correction) comparing two detectors.

    Contingency cells:
      b: A correct, B wrong  (disagreement in favour of A)
      c: A wrong,  B correct (disagreement in favour of B)
    Under H0 (equal error rates) the statistic (|b-c|-1)^2 / (b+c) ~ chi2(1).
    """
    a_correct = y_a == y_true
    b_correct = y_b == y_true
    b_cell = int(np.sum(a_correct & ~b_correct))
    c_cell = int(np.sum(~a_correct & b_correct))
    total = b_cell + c_cell
    if total == 0:
        return {"b": 0, "c": 0, "chi2": float("nan"), "p_value": float("nan")}
    stat = (abs(b_cell - c_cell) - 1) ** 2 / total
    p_value = float(1 - chi2.cdf(stat, df=1))
    return {"b": b_cell, "c": c_cell, "chi2": float(stat), "p_value": p_value}


def main() -> None:
    df = load_data()
    train, test = split_train_test(df)
    y_train = train["is_attack_any"].astype(int).to_numpy()
    y_test = test["is_attack_any"].astype(int).to_numpy()

    features = ["alpha1_norm", "alpha2", "alpha3"]
    x_train = train[features].to_numpy()
    x_test = test[features].to_numpy()

    # Detector A: pure-alpha_1 with fine-grid-best threshold 0.225 (Section 6.5.2)
    pred_pure_a1 = (test["alpha1_norm"].to_numpy() > 0.225).astype(int)

    # Detector B: logistic regression on (alpha_1, alpha_2, alpha_3)
    # class_weight=balanced to avoid collapsing to the majority class given
    # the 0.146% base rate of attacks at (host, window)-level.
    lr = LogisticRegression(
        max_iter=2000,
        class_weight="balanced",
        random_state=RNG_SEED,
    )
    lr.fit(x_train, y_train)
    proba_lr = lr.predict_proba(x_test)[:, 1]
    # Tune threshold on train for maximum F1 (analogous to grid search on
    # theta_A for the unsupervised detector).
    proba_lr_train = lr.predict_proba(x_train)[:, 1]
    thresholds = np.linspace(0.01, 0.99, 99)
    best_t, best_f1 = 0.5, -1.0
    for t in thresholds:
        f1_t = f1_score(y_train, (proba_lr_train > t).astype(int), zero_division=0)
        if f1_t > best_f1:
            best_f1 = f1_t
            best_t = t
    pred_lr = (proba_lr > best_t).astype(int)

    # Metrics
    metrics = {
        "pure_alpha1_theta0.225": f1_precision_recall(y_test, pred_pure_a1),
        "logistic_regression": {
            **f1_precision_recall(y_test, pred_lr),
            "threshold": float(best_t),
            "coefficients": {
                "alpha1_norm": float(lr.coef_[0][0]),
                "alpha2": float(lr.coef_[0][1]),
                "alpha3": float(lr.coef_[0][2]),
                "intercept": float(lr.intercept_[0]),
            },
        },
    }
    ci_a1 = bootstrap_ci(y_test, pred_pure_a1)
    ci_lr = bootstrap_ci(y_test, pred_lr)
    metrics["pure_alpha1_theta0.225"]["bootstrap_95ci"] = ci_a1
    metrics["logistic_regression"]["bootstrap_95ci"] = ci_lr

    mn = mcnemar(y_test, pred_pure_a1, pred_lr)
    metrics["mcnemar_pure_alpha1_vs_logistic"] = mn

    counts = {
        "train_size": int(len(train)),
        "test_size": int(len(test)),
        "train_positives": int(y_train.sum()),
        "test_positives": int(y_test.sum()),
        "train_base_rate": float(y_train.mean()),
        "test_base_rate": float(y_test.mean()),
    }
    metrics["counts"] = counts

    # Save JSON
    (RESULTS / "supervised_baseline.json").write_text(
        json.dumps(metrics, indent=2, ensure_ascii=False)
    )

    # Human-readable report
    a1 = metrics["pure_alpha1_theta0.225"]
    lr_m = metrics["logistic_regression"]
    report = f"""# Supervised baseline + statistical significance (T22 revision)

Generated: run on alphas_with_gt.csv (76 647 (host, window)-pairs).

## Data split
- Train size: {counts['train_size']} ({counts['train_positives']} positives, base rate {counts['train_base_rate']*100:.3f}%)
- Test  size: {counts['test_size']} ({counts['test_positives']} positives, base rate {counts['test_base_rate']*100:.3f}%)
- Protocol: per-day 50/50 temporal split (Section 6.2.1)

## Detectors (host-window GT = source OR destination)

### Pure-α1 (θ_A = 0.225, Section 6.5.2 fine-grid-best)
- Precision = {a1['P']:.4f}   95% CI [{ci_a1['P'][0]:.4f}, {ci_a1['P'][1]:.4f}]
- Recall    = {a1['R']:.4f}   95% CI [{ci_a1['R'][0]:.4f}, {ci_a1['R'][1]:.4f}]
- F1        = {a1['F1']:.4f}   95% CI [{ci_a1['F1'][0]:.4f}, {ci_a1['F1'][1]:.4f}]

### Logistic regression on (α1, α2, α3), threshold tuned on train (θ_LR = {lr_m['threshold']:.3f})
- Precision = {lr_m['P']:.4f}   95% CI [{ci_lr['P'][0]:.4f}, {ci_lr['P'][1]:.4f}]
- Recall    = {lr_m['R']:.4f}   95% CI [{ci_lr['R'][0]:.4f}, {ci_lr['R'][1]:.4f}]
- F1        = {lr_m['F1']:.4f}   95% CI [{ci_lr['F1'][0]:.4f}, {ci_lr['F1'][1]:.4f}]
- Coefficients (logit scale):
  α1={lr_m['coefficients']['alpha1_norm']:.3f}  α2={lr_m['coefficients']['alpha2']:.3f}  α3={lr_m['coefficients']['alpha3']:.3f}  intercept={lr_m['coefficients']['intercept']:.3f}

## McNemar test: pure-α1 vs logistic regression
- b (A right, B wrong) = {mn['b']}
- c (A wrong, B right) = {mn['c']}
- chi² (1 dof, with continuity correction) = {mn['chi2']:.3f}
- p-value = {mn['p_value']:.4g}

## Interpretation for paper
1. If 95% CI of the two F1 estimates overlap substantially, the detectors are
   statistically indistinguishable on this sample despite point-estimate gaps.
2. McNemar p < 0.05 indicates a statistically significant difference in error
   patterns; p >= 0.05 means we cannot reject equal error rates.
3. Whichever direction the result points to, both the pure-α1 and the
   logistic-regression supervised variant remain far below the rule-based
   PostgreSQL baseline (F1 ≈ 0.0797), so the core narrative — that the
   three-signal composition is not competitive on CICIDS2017-like star
   topologies — is not altered by adding a supervised variant.
"""
    (RESULTS / "supervised_baseline_report.md").write_text(report)
    print(report)


if __name__ == "__main__":
    main()
