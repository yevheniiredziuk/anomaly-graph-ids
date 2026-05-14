"""
IF та rule-based baselines на двох ground truth (victim-tagging vs source-only).

Закриває методологічний дефект Таблиці 3 v11: рядки порівнювали методи на різних
GT. Цей скрипт обчислює всі чотири комірки матриці 2×2 (IF×rule-based) × (victim-tagging×source-only),
а також виконує sanity-check для rule-based на victim-GT (очікувано F1≈0,0797).

Вхід:
  data/cleaned/flows_for_postgres.csv (1 668 474 cleaned flows; src_ip, dst_ip,
  t_start, 11 flow-level fields, label).

Вихід:
  results/dual_gt_results.json  — машинні метрики
  results/table3_v12.md         — людиночитаний звіт у форматі Таблиці 3 v12

Терміни:
  - victim-tagging GT: (host, window) позитивний, якщо хост був src АБО dst у
    хоча б одному не-BENIGN flow цього вікна.
  - source-only GT:    (host, window) позитивний, якщо хост був ЛИШЕ src у не-BENIGN
    flow (синонім: attacker-tagging).

Гіперпараметри IF (фіксовані як у плані):
  n_estimators=200, max_samples=256, contamination=0.001, random_state=20260422.
Train/test split: per-day 50/50 temporal (як у scripts/supervised_baseline.py).
Поріг IF: підбір на train за максимізацією F1 на anomaly-score.

Rule-based: точна реімплементація PL/pgSQL детекторів
  (baseline/sql/detectors/01_port_scan.sql, 02_brute_force.sql, 03_dos_flood.sql)
  з тими самими порогами (port_scan>=50 endpoints; brute_force>=100 attempts &
  >=70% short<5s; dos_flood>=1000 flows & >=70% concentration).
"""

import json
import time
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import chi2
from sklearn.ensemble import IsolationForest
from sklearn.metrics import f1_score, precision_score, recall_score

N_BOOTSTRAP = 1000

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results"
FLOWS_CSV = ROOT / "data/cleaned/flows_for_postgres.csv"

PERIOD_START = pd.Timestamp("2017-07-04 00:00:00+00:00")
PERIOD_END = pd.Timestamp("2017-07-06 00:00:00+00:00")
WINDOW = "5min"

RNG_SEED = 20260422
IF_PARAMS = dict(
    n_estimators=200,
    max_samples=256,
    contamination=0.001,
    random_state=RNG_SEED,
    n_jobs=-1,
)

# Пороги rule-based детекторів (з SQL):
PORT_SCAN_THRESHOLD = 50          # >=50 distinct (dst_ip, dst_port) пар
BRUTE_FORCE_ATTEMPTS = 100        # >=100 attempts
BRUTE_FORCE_SHORT_RATIO = 0.7     # >=70% потоків <5s
BRUTE_FORCE_SHORT_US = 5_000_000  # 5s = 5_000_000 мкс
DOS_FLOW_THRESHOLD = 1000         # >=1000 flows на жертву
DOS_CONCENTRATION = 0.7           # >=70% від одного джерела


# ---------------------------------------------------------------------------
# 1. Завантаження та підготовка даних
# ---------------------------------------------------------------------------
def load_flows() -> pd.DataFrame:
    print(f"[load] {FLOWS_CSV.name}")
    t0 = time.time()
    df = pd.read_csv(FLOWS_CSV, parse_dates=["t_start", "t_end"])
    print(f"  {len(df)} рядків за {time.time()-t0:.1f}с")
    df = df.loc[(df["t_start"] >= PERIOD_START) & (df["t_start"] < PERIOD_END)].copy()
    df["window_start"] = df["t_start"].dt.floor(WINDOW)
    df["is_attack"] = df["label"] != "BENIGN"
    print(f"  Tue+Wed: {len(df)} flows, {df['window_start'].nunique()} вікон, "
          f"{df['is_attack'].sum()} attack-flows")
    return df


# ---------------------------------------------------------------------------
# 2. Universe та GT для двох режимів
# ---------------------------------------------------------------------------
def build_universe_and_gt(df: pd.DataFrame) -> dict:
    """Повертає для кожного режиму dict з полями {universe_df, gt_set}."""
    src_pairs = (df[["source_ip", "window_start"]]
                 .rename(columns={"source_ip": "host"})
                 .drop_duplicates())
    dst_pairs = (df[["destination_ip", "window_start"]]
                 .rename(columns={"destination_ip": "host"})
                 .drop_duplicates())
    victim_universe = pd.concat([src_pairs, dst_pairs]).drop_duplicates().reset_index(drop=True)
    src_only_universe = src_pairs.drop_duplicates().reset_index(drop=True)

    attack = df[df["is_attack"]]
    gt_src = (attack[["source_ip", "window_start"]]
              .rename(columns={"source_ip": "host"})
              .drop_duplicates())
    gt_dst = (attack[["destination_ip", "window_start"]]
              .rename(columns={"destination_ip": "host"})
              .drop_duplicates())
    gt_victim = pd.concat([gt_src, gt_dst]).drop_duplicates()

    return {
        "victim": {
            "universe": victim_universe,
            "gt_set": set(map(tuple, gt_victim.itertuples(index=False))),
        },
        "source": {
            "universe": src_only_universe,
            "gt_set": set(map(tuple, gt_src.drop_duplicates().itertuples(index=False))),
        },
    }


# ---------------------------------------------------------------------------
# 3. Побудова 21 host-window-ознаки (симетрично src/dst)
# ---------------------------------------------------------------------------
FEATURE_NAMES = [
    "flow_count", "as_src_count", "as_dst_count",
    "flow_duration_mean", "flow_duration_max", "flow_duration_std",
    "bytes_fwd_sum", "bytes_bwd_sum",
    "packets_fwd_sum", "packets_bwd_sum",
    "mean_pkt_size", "mean_packets_per_flow",
    "syn_count_sum", "rst_count_sum", "psh_count_sum",
    "ack_count_sum", "fin_count_sum", "urg_count_sum",
    "unique_remote_ips", "unique_remote_ports", "log_flow_count",
]
assert len(FEATURE_NAMES) == 21


def build_features(df: pd.DataFrame, universe: pd.DataFrame, *, source_only: bool) -> pd.DataFrame:
    """Агрегує flows у 21-ознаку на (host, window).

    Якщо source_only=True — обчислюємо тільки за flows, де host==src.
    Інакше — за всіма flows, де host==src АБО host==dst (симетрично).
    """
    print(f"[features] source_only={source_only}, universe={len(universe)}")
    t0 = time.time()

    if source_only:
        a = df.rename(columns={"source_ip": "host", "destination_ip": "remote",
                               "destination_port": "remote_port"})
        a["role_is_src"] = True
        flows = a[["host", "remote", "remote_port", "window_start", "flow_duration_us",
                   "bytes_fwd", "bytes_bwd", "packets_fwd", "packets_bwd",
                   "syn_count", "rst_count", "psh_count", "ack_count",
                   "fin_count", "urg_count", "role_is_src"]].copy()
    else:
        a = df.rename(columns={"source_ip": "host", "destination_ip": "remote",
                               "destination_port": "remote_port"}).copy()
        a["role_is_src"] = True
        b = df.rename(columns={"destination_ip": "host", "source_ip": "remote",
                               "source_port": "remote_port"}).copy()
        b["role_is_src"] = False
        cols = ["host", "remote", "remote_port", "window_start", "flow_duration_us",
                "bytes_fwd", "bytes_bwd", "packets_fwd", "packets_bwd",
                "syn_count", "rst_count", "psh_count", "ack_count",
                "fin_count", "urg_count", "role_is_src"]
        flows = pd.concat([a[cols], b[cols]], ignore_index=True)

    g = flows.groupby(["host", "window_start"], sort=False)
    feats = pd.DataFrame({
        "flow_count": g.size(),
        "as_src_count": g["role_is_src"].sum(),
        "flow_duration_mean": g["flow_duration_us"].mean(),
        "flow_duration_max": g["flow_duration_us"].max(),
        "flow_duration_std": g["flow_duration_us"].std().fillna(0.0),
        "bytes_fwd_sum": g["bytes_fwd"].sum(),
        "bytes_bwd_sum": g["bytes_bwd"].sum(),
        "packets_fwd_sum": g["packets_fwd"].sum(),
        "packets_bwd_sum": g["packets_bwd"].sum(),
        "syn_count_sum": g["syn_count"].sum(),
        "rst_count_sum": g["rst_count"].sum(),
        "psh_count_sum": g["psh_count"].sum(),
        "ack_count_sum": g["ack_count"].sum(),
        "fin_count_sum": g["fin_count"].sum(),
        "urg_count_sum": g["urg_count"].sum(),
        "unique_remote_ips": g["remote"].nunique(),
        "unique_remote_ports": g["remote_port"].nunique(),
    }).reset_index()

    feats["as_dst_count"] = feats["flow_count"] - feats["as_src_count"]
    total_pkts = feats["packets_fwd_sum"] + feats["packets_bwd_sum"]
    total_bytes = feats["bytes_fwd_sum"] + feats["bytes_bwd_sum"]
    feats["mean_pkt_size"] = np.where(total_pkts > 0, total_bytes / total_pkts.replace(0, np.nan), 0.0).astype(float)
    feats["mean_pkt_size"] = feats["mean_pkt_size"].fillna(0.0)
    feats["mean_packets_per_flow"] = total_pkts / feats["flow_count"]
    feats["log_flow_count"] = np.log1p(feats["flow_count"])

    # Залишаємо тільки 21 ознаку у фіксованому порядку + key
    feats = feats[["host", "window_start"] + FEATURE_NAMES].copy()

    # Reindex до universe (на випадок якщо у universe є зайві пари — не повинно бути)
    universe_keyed = universe.merge(feats, on=["host", "window_start"], how="inner")
    print(f"  feature rows: {len(universe_keyed)} ({time.time()-t0:.1f}с)")
    assert len(universe_keyed) == len(universe), \
        f"universe drift: {len(universe_keyed)} vs {len(universe)}"
    return universe_keyed


# ---------------------------------------------------------------------------
# 4. Train/test split: per-day 50/50 temporal
# ---------------------------------------------------------------------------
def per_day_split(feats: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    feats = feats.copy()
    feats["day"] = feats["window_start"].dt.day
    parts = []
    for _, day_df in feats.groupby("day"):
        cutoff = day_df["window_start"].quantile(0.5)
        day_df = day_df.assign(is_train=day_df["window_start"] < cutoff)
        parts.append(day_df)
    full = pd.concat(parts, ignore_index=True)
    return full[full["is_train"]].drop(columns=["is_train"]), full[~full["is_train"]].drop(columns=["is_train"])


def attach_gt(feats: pd.DataFrame, gt_set: set) -> pd.DataFrame:
    feats = feats.copy()
    keys = list(zip(feats["host"], feats["window_start"]))
    feats["y"] = [int(k in gt_set) for k in keys]
    return feats


# ---------------------------------------------------------------------------
# 5. Метрики
# ---------------------------------------------------------------------------
def metrics(y_true: np.ndarray, y_pred: np.ndarray) -> dict:
    tp = int(((y_true == 1) & (y_pred == 1)).sum())
    fp = int(((y_true == 0) & (y_pred == 1)).sum())
    fn = int(((y_true == 1) & (y_pred == 0)).sum())
    tn = int(((y_true == 0) & (y_pred == 0)).sum())
    return {
        "P": float(precision_score(y_true, y_pred, zero_division=0)),
        "R": float(recall_score(y_true, y_pred, zero_division=0)),
        "F1": float(f1_score(y_true, y_pred, zero_division=0)),
        "TP": tp, "FP": fp, "FN": fn, "TN": tn,
        "n": int(len(y_true)),
        "n_pos": int(y_true.sum()),
    }


def bootstrap_ci(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    n_resamples: int = N_BOOTSTRAP,
    seed: int = RNG_SEED,
) -> dict:
    """Стратифікований bootstrap 95% CI для P/R/F1.

    Стратифіковано за y_true для збереження base rate у resample-ах
    (точно як у scripts/supervised_baseline.py:bootstrap_ci).
    """
    rng = np.random.default_rng(seed)
    pos_idx = np.where(y_true == 1)[0]
    neg_idx = np.where(y_true == 0)[0]
    if len(pos_idx) == 0 or len(neg_idx) == 0:
        return {"P": (0.0, 0.0), "R": (0.0, 0.0), "F1": (0.0, 0.0)}

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

    return {"P": ci(ps), "R": ci(rs), "F1": ci(f1s)}


def mcnemar(y_true: np.ndarray, y_a: np.ndarray, y_b: np.ndarray) -> dict:
    """Критерій Мак-Немара з поправкою на неперервність.

    b: A правий, B помиляється;  c: A помиляється, B правий.
    Під H0 (рівні error rates) (|b-c|-1)^2/(b+c) ~ chi2(1).
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


# ---------------------------------------------------------------------------
# 6. IF: train on train (anomaly score), threshold-tune by max F1 on train
# ---------------------------------------------------------------------------
def run_if(train: pd.DataFrame, test: pd.DataFrame) -> dict:
    X_train = train[FEATURE_NAMES].to_numpy(dtype=np.float64)
    X_test = test[FEATURE_NAMES].to_numpy(dtype=np.float64)
    y_train = train["y"].to_numpy()
    y_test = test["y"].to_numpy()

    X_train = np.nan_to_num(X_train, nan=0.0, posinf=0.0, neginf=0.0)
    X_test = np.nan_to_num(X_test, nan=0.0, posinf=0.0, neginf=0.0)

    clf = IsolationForest(**IF_PARAMS)
    clf.fit(X_train)

    # score_samples: вищий = нормальніший. anomaly_score = -score_samples → вищий = аномальніший
    train_anom = -clf.score_samples(X_train)
    test_anom = -clf.score_samples(X_test)

    # Поріг — підбір на train за максимізацією F1 (як у supervised_baseline.py)
    candidates = np.quantile(train_anom, np.linspace(0.5, 0.999, 200))
    best_thr, best_f1 = float("-inf"), -1.0
    for thr in candidates:
        pred_t = (train_anom > thr).astype(int)
        f1_t = f1_score(y_train, pred_t, zero_division=0)
        if f1_t > best_f1:
            best_f1, best_thr = f1_t, float(thr)

    pred_test = (test_anom > best_thr).astype(int)
    m = metrics(y_test, pred_test)
    m["threshold"] = best_thr
    m["train_F1_at_threshold"] = float(best_f1)
    m["bootstrap_95ci"] = bootstrap_ci(y_test, pred_test)
    # Зберігаємо raw arrays для подальшого McNemar та per-attack recall
    m["_y_test"] = y_test
    m["_pred_test"] = pred_test
    return m


# ---------------------------------------------------------------------------
# 7. Rule-based: реімплементація PL/pgSQL у pandas (per 5-min window)
# ---------------------------------------------------------------------------
def run_rules(df_flows: pd.DataFrame) -> set:
    """Повертає множину (host, window_start) — об'єднання предсказань трьох
    rule-based детекторів. Кожен detection (src, dst, window) розгортається у
    ДВІ пари: (src, window) і (dst, window) — точно як у Java
    BaselinePredictionsCollector.fetchAll (рядки 41-42), що віддзеркалює
    рішення «обидва ендпоінти підозрілі». port_scan не має dst (NULL у SQL),
    тож додає лише src.
    """
    print("[rules] обчислення прогнозів rule-based детекторів")
    t0 = time.time()
    preds: set[tuple] = set()

    df_flows = df_flows.copy()
    df_flows["dst_pair"] = (df_flows["destination_ip"].astype(str) + ":" +
                            df_flows["destination_port"].astype(str))

    # 1) port_scan: src з >=50 distinct (dst_ip, dst_port). dst_ip=NULL → тільки src
    ps = (df_flows.groupby(["window_start", "source_ip"])["dst_pair"]
          .nunique().reset_index(name="uniq"))
    scanners = ps[ps["uniq"] >= PORT_SCAN_THRESHOLD]
    preds.update(zip(scanners["source_ip"], scanners["window_start"]))
    print(f"  port_scan detections: {len(scanners)} (тільки src, dst=NULL)")

    # 2) brute_force: (src, dst, dst_port) >=100 attempts AND >=70% short (<5s)
    #    Розгортаємо у (src, window) + (dst, window).
    bf = (df_flows.assign(short=(df_flows["flow_duration_us"] < BRUTE_FORCE_SHORT_US).astype(int))
          .groupby(["window_start", "source_ip", "destination_ip", "destination_port"])
          .agg(attempts=("flow_duration_us", "size"), short_sum=("short", "sum"))
          .reset_index())
    bf["short_ratio"] = bf["short_sum"] / bf["attempts"]
    bf_hits = bf[(bf["attempts"] >= BRUTE_FORCE_ATTEMPTS) & (bf["short_ratio"] >= BRUTE_FORCE_SHORT_RATIO)]
    preds.update(zip(bf_hits["source_ip"], bf_hits["window_start"]))
    preds.update(zip(bf_hits["destination_ip"], bf_hits["window_start"]))
    print(f"  brute_force detections: {len(bf_hits)} (src + dst)")

    # 3) dos_flood: dst >=1000 flows AND top src >=70%. Розгортаємо у src + dst.
    per_src = (df_flows.groupby(["window_start", "destination_ip", "source_ip"])
               .size().reset_index(name="src_flows"))
    per_dst_agg = (per_src.groupby(["window_start", "destination_ip"])
                   .agg(total_flows=("src_flows", "sum"),
                        max_src=("src_flows", "max"))
                   .reset_index())
    per_dst = per_dst_agg.merge(per_src, on=["window_start", "destination_ip"])
    top = per_dst[per_dst["src_flows"] == per_dst["max_src"]]
    victims = top[(top["total_flows"] >= DOS_FLOW_THRESHOLD) &
                  (top["max_src"] / top["total_flows"] >= DOS_CONCENTRATION)]
    victims = victims.drop_duplicates(["window_start", "destination_ip"])
    preds.update(zip(victims["source_ip"], victims["window_start"]))
    preds.update(zip(victims["destination_ip"], victims["window_start"]))
    print(f"  dos_flood detections: {len(victims)} (src + dst)")

    print(f"  TOTAL unique (host, window) predictions: {len(preds)} ({time.time()-t0:.1f}с)")
    return preds


def evaluate_rules(rule_preds: set, universe: pd.DataFrame, gt_set: set,
                   *, with_bootstrap: bool = True) -> dict:
    keys = list(zip(universe["host"], universe["window_start"]))
    y_true = np.array([int(k in gt_set) for k in keys])
    y_pred = np.array([int(k in rule_preds) for k in keys])
    m = metrics(y_true, y_pred)
    if with_bootstrap:
        m["bootstrap_95ci"] = bootstrap_ci(y_true, y_pred)
    m["_y_true"] = y_true
    m["_y_pred"] = y_pred
    return m


# ---------------------------------------------------------------------------
# 8. Головний прогін
# ---------------------------------------------------------------------------
def per_attack_recall(df: pd.DataFrame, test_feats: pd.DataFrame, pred_test: np.ndarray,
                       *, source_only: bool) -> dict:
    """Recall IF за типами атак на test-set (per-attack analog Таблиці 6.4)."""
    test_feats = test_feats.copy()
    test_feats["_pred"] = pred_test
    test_keys = set(zip(test_feats["host"], test_feats["window_start"]))
    test_pos_pred = test_feats[test_feats["_pred"] == 1]
    pred_set = set(zip(test_pos_pred["host"], test_pos_pred["window_start"]))

    attack_df = df[df["label"] != "BENIGN"]
    out = {}
    for label, sub in attack_df.groupby("label"):
        # GT: pairs (host, window) для цього attack-type, що потрапили у test universe
        if source_only:
            gt_pairs = set(zip(sub["source_ip"], sub["window_start"]))
        else:
            src_p = set(zip(sub["source_ip"], sub["window_start"]))
            dst_p = set(zip(sub["destination_ip"], sub["window_start"]))
            gt_pairs = src_p | dst_p
        gt_in_test = gt_pairs & test_keys
        if not gt_in_test:
            continue
        tp = len(gt_in_test & pred_set)
        out[label] = {"gt_in_test": len(gt_in_test), "tp": tp,
                      "recall": tp / len(gt_in_test) if gt_in_test else 0.0}
    return out


def strip_arrays(d: dict) -> dict:
    """Видаляє службові _y_*/_pred_* numpy масиви для серіалізації в JSON."""
    return {k: v for k, v in d.items() if not k.startswith("_")}


def main() -> None:
    df = load_flows()
    sets = build_universe_and_gt(df)

    print()
    print("=" * 70)
    print("UNIVERSE & GT")
    print("=" * 70)
    print(f"victim:      n={len(sets['victim']['universe'])}, n+={len(sets['victim']['gt_set'])}")
    print(f"source-only: n={len(sets['source']['universe'])}, n+={len(sets['source']['gt_set'])}")

    # ---- 21-feature aggregation ----
    feats_victim = build_features(df, sets["victim"]["universe"], source_only=False)
    feats_victim = attach_gt(feats_victim, sets["victim"]["gt_set"])
    feats_source = build_features(df, sets["source"]["universe"], source_only=True)
    feats_source = attach_gt(feats_source, sets["source"]["gt_set"])

    train_v, test_v = per_day_split(feats_victim)
    train_s, test_s = per_day_split(feats_source)
    print()
    print(f"victim split:      train={len(train_v)} (n+={train_v['y'].sum()}), "
          f"test={len(test_v)} (n+={test_v['y'].sum()})")
    print(f"source-only split: train={len(train_s)} (n+={train_s['y'].sum()}), "
          f"test={len(test_s)} (n+={test_s['y'].sum()})")

    print()
    print("=" * 70)
    print("ПРОГОН A: IF на 21 flow-ознаці на victim-tagging GT")
    print("=" * 70)
    if_victim = run_if(train_v, test_v)
    print(json.dumps(strip_arrays(if_victim), indent=2, ensure_ascii=False))

    print()
    print("=" * 70)
    print("ПРОГОН C-1 (sanity): IF на 21 flow-ознаці на source-only GT")
    print("=" * 70)
    if_source = run_if(train_s, test_s)
    print(json.dumps(strip_arrays(if_source), indent=2, ensure_ascii=False))

    # ---- Rule-based ----
    print()
    print("=" * 70)
    print("Rule-based: обчислення прогнозів (один раз)")
    print("=" * 70)
    rule_preds = run_rules(df)

    print()
    print("=" * 70)
    print("ПРОГОН B: Rule-based на source-only GT")
    print("=" * 70)
    rule_source = evaluate_rules(rule_preds, sets["source"]["universe"], sets["source"]["gt_set"])
    print(json.dumps(strip_arrays(rule_source), indent=2, ensure_ascii=False))

    print()
    print("=" * 70)
    print("ПРОГОН C-2 (sanity): Rule-based на victim-tagging GT [очікувано F1≈0,0797]")
    print("=" * 70)
    rule_victim = evaluate_rules(rule_preds, sets["victim"]["universe"], sets["victim"]["gt_set"])
    print(json.dumps(strip_arrays(rule_victim), indent=2, ensure_ascii=False))

    # ---- McNemar IF vs rule-based на спільному test-set ----
    print()
    print("=" * 70)
    print("McNemar: IF vs rule-based на ТЕСТОВОМУ ПІДМНОЖИНІ кожного universe")
    print("=" * 70)
    rule_victim_on_test = evaluate_rules(rule_preds, test_v[["host", "window_start"]],
                                          sets["victim"]["gt_set"], with_bootstrap=False)
    rule_source_on_test = evaluate_rules(rule_preds, test_s[["host", "window_start"]],
                                          sets["source"]["gt_set"], with_bootstrap=False)
    mn_victim = mcnemar(if_victim["_y_test"], if_victim["_pred_test"], rule_victim_on_test["_y_pred"])
    mn_source = mcnemar(if_source["_y_test"], if_source["_pred_test"], rule_source_on_test["_y_pred"])
    print("McNemar (victim, IF vs rule):", mn_victim)
    print("McNemar (source-only, IF vs rule):", mn_source)

    # ---- Per-attack recall для IF ----
    print()
    print("=" * 70)
    print("Per-attack recall: IF на test-set")
    print("=" * 70)
    pa_victim = per_attack_recall(df, test_v, if_victim["_pred_test"], source_only=False)
    pa_source = per_attack_recall(df, test_s, if_source["_pred_test"], source_only=True)
    print("victim:", json.dumps(pa_victim, indent=2, ensure_ascii=False))
    print("source-only:", json.dumps(pa_source, indent=2, ensure_ascii=False))

    # ---- збереження ----
    out = {
        "config": {
            "period": [str(PERIOD_START), str(PERIOD_END)],
            "window": WINDOW,
            "if_params": {**IF_PARAMS, "n_jobs": IF_PARAMS["n_jobs"]},
            "rule_thresholds": {
                "port_scan_endpoints_min": PORT_SCAN_THRESHOLD,
                "brute_force_attempts_min": BRUTE_FORCE_ATTEMPTS,
                "brute_force_short_ratio_min": BRUTE_FORCE_SHORT_RATIO,
                "brute_force_short_us": BRUTE_FORCE_SHORT_US,
                "dos_flow_min": DOS_FLOW_THRESHOLD,
                "dos_concentration_min": DOS_CONCENTRATION,
            },
            "feature_count": 21,
            "feature_names": FEATURE_NAMES,
            "split": "per-day 50/50 temporal",
            "rng_seed": RNG_SEED,
        },
        "universe": {
            "victim_n": len(sets["victim"]["universe"]),
            "victim_n_pos": len(sets["victim"]["gt_set"]),
            "source_n": len(sets["source"]["universe"]),
            "source_n_pos": len(sets["source"]["gt_set"]),
        },
        "if_victim_runA": strip_arrays(if_victim),
        "if_source_sanity_C1": strip_arrays(if_source),
        "rule_source_runB": strip_arrays(rule_source),
        "rule_victim_sanity_C2": strip_arrays(rule_victim),
        "mcnemar_if_vs_rule_victim": mn_victim,
        "mcnemar_if_vs_rule_source": mn_source,
        "per_attack_recall_if_victim": pa_victim,
        "per_attack_recall_if_source": pa_source,
    }
    (RESULTS / "dual_gt_results.json").write_text(json.dumps(out, indent=2, ensure_ascii=False))
    print(f"\n[saved] {RESULTS/'dual_gt_results.json'}")


if __name__ == "__main__":
    main()
