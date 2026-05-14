"""
Емпірична верифікація степеня FTP/SSH-Patator атакувальників у 5-хв вікнах.

Закриває зауваження З.6 рецензії v13: аргумент про інваріантність betweenness
centrality до вибору Δt_a для brute-force-кампаній був представлений у v13
лише аналітично; рецензент справедливо вимагає емпіричної верифікації.

Атакувальники у CICIDS2017:
  - FTP-Patator (Tuesday): src = 172.16.0.1  → dst = 192.168.10.50:21
  - SSH-Patator (Tuesday): src = 172.16.0.1  → dst = 192.168.10.50:22

Для кожного 5-хв вікна, що містить хоча б один атаковий потік від цього
атакувальника, обчислюємо:
  degree = кількість різних dst_ip, до яких атакувальник підключався у вікні
           (у простій GDS-проекції з агрегацією count(r) AS weight).

Очікуваний результат: degree = 1 для всіх вікон (одноцільова атака на
192.168.10.50), що підтверджує BC(v) = 0 тотожно і робить нечутливим вибір
Δt_a у діапазоні {10, 30, 60} с.

Вихід:
  results/attacker_degree_brute_force.json
"""

import json
import time
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results"
FLOWS_CSV = ROOT / "data/cleaned/flows_for_postgres.csv"

ATTACKERS = {
    "FTP-Patator": ("172.16.0.1", "FTP-Patator"),
    "SSH-Patator": ("172.16.0.1", "SSH-Patator"),
}
WINDOW = "5min"


def main() -> None:
    print("=" * 70)
    print("ВЕРИФІКАЦІЯ degree(attacker) ДЛЯ FTP/SSH-Patator у 5-хв вікнах")
    print("=" * 70)

    print(f"[load] {FLOWS_CSV.name}")
    t0 = time.time()
    df = pd.read_csv(FLOWS_CSV, parse_dates=["t_start", "t_end"])
    print(f"  {len(df)} рядків за {time.time()-t0:.1f}с")
    df["window_start"] = df["t_start"].dt.floor(WINDOW)

    out = {}

    for attack_name, (attacker_ip, label) in ATTACKERS.items():
        print(f"\n--- {attack_name} (src = {attacker_ip}) ---")
        # Вибираємо лише атакові потоки цього класу
        attack_flows = df[(df["label"] == label) & (df["source_ip"] == attacker_ip)]
        print(f"  Атакових потоків: {len(attack_flows)}")
        if len(attack_flows) == 0:
            print("  Не знайдено — пропускаємо")
            continue

        # На кожне 5-хв вікно, що містить атакові потоки, обчислюємо
        # degree(attacker) = кількість унікальних dst_ip, до яких хост звертався
        # У простій GDS-проекції (агрегація count(r) AS weight) це і є степінь.
        # Беремо ВСІ потоки атакувальника у вікні (не лише атакові), бо для
        # graph projection нерелевантно, який це потік.
        attack_windows = sorted(attack_flows["window_start"].unique())
        print(f"  Вікон з атакою: {len(attack_windows)}")

        per_window = []
        for w in attack_windows:
            all_flows_in_window = df[(df["source_ip"] == attacker_ip) &
                                     (df["window_start"] == w)]
            unique_dst = all_flows_in_window["destination_ip"].nunique()
            attack_dst = attack_flows[attack_flows["window_start"] == w]["destination_ip"].nunique()
            per_window.append({
                "window_start": str(w),
                "degree_simple_graph": int(unique_dst),  # = кількість унікальних dst_ip
                "unique_attack_destinations": int(attack_dst),
                "total_flows_in_window": int(len(all_flows_in_window)),
            })

        degrees = [pw["degree_simple_graph"] for pw in per_window]
        out[attack_name] = {
            "attacker_ip": attacker_ip,
            "label_value": label,
            "n_windows_with_attack": len(attack_windows),
            "degree_min": min(degrees),
            "degree_max": max(degrees),
            "degree_median": float(sorted(degrees)[len(degrees) // 2]),
            "fraction_windows_degree_eq_1": sum(1 for d in degrees if d == 1) / len(degrees),
            "per_window": per_window,
        }
        print(f"  degree(attacker): min = {min(degrees)}, max = {max(degrees)}, "
              f"median = {sorted(degrees)[len(degrees)//2]}")
        print(f"  Частка вікон з degree = 1: {out[attack_name]['fraction_windows_degree_eq_1']:.2%}")
        if max(degrees) > 1:
            print(f"  Вікна з degree > 1:")
            for pw in per_window:
                if pw["degree_simple_graph"] > 1:
                    print(f"    {pw['window_start']}: degree = {pw['degree_simple_graph']}, "
                          f"total_flows = {pw['total_flows_in_window']}")

    out_path = RESULTS / "attacker_degree_brute_force.json"
    out_path.write_text(json.dumps(out, indent=2, ensure_ascii=False))
    print(f"\n[saved] {out_path}")

    print("\n" + "=" * 70)
    print("ПІДСУМОК")
    print("=" * 70)
    for name, data in out.items():
        ok = data["fraction_windows_degree_eq_1"] == 1.0
        verdict = "✓ ПІДТВЕРДЖЕНО (degree = 1 у 100% вікон)" if ok else "✗ Є винятки"
        print(f"{name}: degree max = {data['degree_max']} — {verdict}")


if __name__ == "__main__":
    main()
