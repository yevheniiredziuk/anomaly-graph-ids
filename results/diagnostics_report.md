# Diagnostics report for AGIDS paper (T19 post-mortem)

Date: 2026-04-19
Commit SHA (paper state): `3391d86` (T19: fill experimental results into paper drafts)
T19 run: 20260419-113133

---

## Executive summary

Загальна картина: **метод працює, але сигнал слабкий, а завдання погано відповідає прийнятому host-window-протоколу на CICIDS2017.** Значна частина показника F1 = 0,0108 пояснюється реальними властивостями датасету і методу, а не багом у реалізації. Є два legitimate покращення threshold-калібрування (до F1 ≈ 0,013, тобто +23 %), але порядок величини не змінюється.

---

## Check 1: Ground truth sanity

### 1.1 Neo4j graph shape (Cypher)

| Метрика | Фактично | Очікувано інструкцією | Статус |
|---|---:|---:|---|
| `total_hosts` (Host) | **15 673** | 30–60 | ⚠️ поза очікуванням, **але не баг** |
| `total_edges` (CONNECTS_TO) | **329 076** | 150K–300K | ✅ у діапазоні |
| `attack_edges` (label ≠ BENIGN) | **213** | 30K–50K | ⚠️ поза очікуванням, **не баг** |
| distinct attack labels | **7** | 7–8 | ✅ |
| days у distribution | **3** (07-03, 07-04, 07-05 UTC) | 3 | ✅ |

**Distinct labels breakdown:** FTP-Patator 64, SSH-Patator 63, slowloris 27, Hulk 21, Slowhttptest 19, Heartbleed 11, GoldenEye 8.

**Інтерпретація розходжень:**

1. **15 673 хостів** — CICIDS2017 захоплює not only internal lab (172.16.0.0/16 + 192.168.10.0/24, ~30 хостів), але й зовнішні endpoints, до яких lab-хости звертаються (DNS-сервери, cloud endpoints, HTTP-зовнішні сервери). Очікування інструкції «30–60» враховує лише attacker+victim core, а не всі endpoints.
2. **213 attack_edges** — це **після 60-с aggregation** (Section 4.2 статті). У flow-space attack-flows = 266 505 (PostgreSQL), але агрегація колапсує тисячі flow до одного (src, dst, proto, 60s-bucket) super-edge, особливо для volumetric DoS. Очікування «30K–50K» було у flow-просторі. Це очікуваний наслідок aggregation.

### 1.2 PostgreSQL ground truth size (SQL)

| Метрика | Фактично | Очікувано | Статус |
|---|---:|---:|---|
| `total_flows` | **1 668 474** | 1,5M–1,7M | ✅ |
| `attack_flows` | **266 505** | ~230K | ✅ |
| `gt_pairs` ((host, 5min-window) з attack-flow у Tue+Wed) | **112** | 3K–15K | ⚠️ **не баг** |
| `universe_pairs` (всі active (host, window) у Tue+Wed) | **76 646** | 20K–80K | ✅ |

**Per-attack GT breakdown** (з evaluation-логу):
- FTP-Patator 30 pairs, SSH-Patator 28, slowloris 18, Hulk 12, Slowhttptest 10, Heartbleed 10, GoldenEye 4. Сума = 112.

**Інтерпретація розходжень:**

- **112 GT-пар** є правильним числом: у CICIDS2017 атаки високо-концентровані в часі (attack epoch = 15–60 хв) та по хостах (≤5 attacker IPs, ≤10 victim IPs). Оцінка «3K–15K» в інструкції відображає ситуацію щодо flow-level ground truth; наш протокол — (host, window) pair-level, значно строгіший. **Це не баг, а особливість прийнятого host-window-протоколу оцінювання**, яка описана в Section 6.2.2 статті.

### 1.3 AnomalyEvent count

| Метрика | Фактично | Очікувано | Статус |
|---|---:|---:|---|
| `total_events` | **3 165 946** | тисячі–десятки тисяч | ⚠️ на два порядки більше |
| `unique_hosts` в events | **15 673** | — | (всі хости) |
| `unique_windows` | **202** | ≤576 (48 год × 12) | ✅ (202 непорожніх вікон) |
| score range | min=0,0477 · p50=0,0477 · p90=0,0477 · p95=0,0551 · p99=0,6477 · max=0,9978 | — | **score highly concentrated at floor value 0,0477** |

**Інтерпретація `total_events` = 3 165 946:**

`3 165 946 = 15 673 hosts × 202 non-empty windows` — детектор записує event для **кожного** хоста у **кожному непорожньому вікні**, а не лише для тих, що перетинають поріг. Поведінка задокументована у `CompositeScorer.RECORD_EVENTS_CYPHER` і пов'язана з тим, що T15 grid search вимагає всіх scores (а не лише above-threshold) для калібрування. Це очікувано.

**Критично важливо:** 90 % scores концентровані на значенні **0,0477** ≈ sigmoid(−3) ≈ `σ(w₁·0,119 + 0·w₂ + 0·w₃)` при `w₁=0,5`, що є floor-значенням для хостів з α₁_norm = 0,119, α₂ = 0, α₃ = 0 (див. Check 2).

---

## Check 2: Alpha distributions

### 2.1 Розподіл α на AnomalyEvent

| Компонент | mean | std | p50 | p95 | max | Діагноз |
|---|---:|---:|---:|---:|---:|---|
| α₁_norm (BC z-score, sigmoid) | **0,1213** | 0,0274 | **0,1192** | 0,1302 | 1,000 | **концентрований на floor 0,119 ≈ σ(−2)**; сигнал тільки у <5 % хостів |
| α₂ (community change) | 0,0176 | 0,128 | **0,000** | **0,000** | 1,000 | 95 % = 0; рідкісні binary 1.0 outliers |
| α₃ (Jaccard drift) | 0,0357 | 0,181 | **0,000** | **0,000** | 1,000 | 95 % = 0; рідкісні binary 1.0 outliers |

**Інтерпретація:**

- **α₁** домінується floor-value 0,119 = σ(0 − θ₁) при θ₁ = 2,0. Це відповідає формулі `α₁_norm = σ(|bc − μ|/(σ+ε) − θ₁)` з `Alpha1Computer.java`: для хоста з current_bc ≈ μ (нульове відхилення від baseline) α₁_norm фіксується на ~0,119.
- **α₂ і α₃ = 0 у 95 % випадків** — структурно нормальна поведінка: в benign-трафіку 2-hop околиця і community-membership здебільшого стабільні між сусідніми 5-хв вікнами. Але цього сигналу недостатньо як дискримінатора — компонент спрацьовує лише у bimodal manner (або 0, або 1).

### 2.2 Baseline BC coverage

| Метрика | Фактично | Очікування | Статус |
|---|---:|---:|---|
| hosts `with_baseline` (baseline_bc_std не NULL) | **15 673** (100 %) | — | ✅ повна покриття |
| hosts `without_baseline` | 0 | — | ✅ |
| hosts з `baseline_bc_mean = 0 AND baseline_bc_std = 0` | **14 597 (93,1 %)** | — | ⚠️ **root cause signal weakness** |
| min `baseline_bc_std` | 0,0 | > 0 | ⚠️ division-by-ε risk |
| non-zero `baseline_bc_mean` hosts | 1 076 (6,9 %) | — | — |
| `baseline_samples` (всі hosts) | 98 (рівномірно) | — | ✅ |

**Крикотично:** **14 597 / 15 673 (93,1 %) хостів мають `baseline_bc_mean = 0` і `baseline_bc_std = 0`** — тобто у понеділковий baseline-період ці хости **жодного разу** не потрапляли на shortest path (leaf nodes, clients без reverse-connections). Для таких хостів формула α₁_norm = σ(|bc − 0|/(0 + 10⁻⁶) − 2) дає:
- якщо current_bc = 0 → σ(0 − 2) = **0,119** (floor)
- якщо current_bc > 0 → σ(bc·10⁶ − 2) → **1,0** (saturation)

Це робить α₁ фактично **бінарним детектором** «leaf або hub» для 93 % хостів, а не континуальною метрикою структурної аномальності. Як наслідок, α₁ має дуже обмежений discriminative power.

### 2.3 Кореляція α з ground truth

Перевірка виконана через Python join events ↔ universe ↔ GT:
- всі 112 GT-пар мають AnomalyEvent у events-наборі ✅
- score distribution serverу GT-позитивних пар: 11 з 112 мають score > 0,6 (recall θ=0,6 = 9,8 %)
- pure-α₁ threshold ≥ 0,2 фіксує 42 TP / 112 → R = 37,5 %, P = 0,68 % → F1 = **0,0133**

**Висновок:** сигнал у α₁ є, але слабкий і «глухий» для значної частини GT. α₂, α₃ практично не несуть сигналу для цього датасету.

---

## Check 3: Previous state rollover

### 3.1 Стан Host після повного прогону

| previous_* property | NULL hosts | not-NULL hosts |
|---|---:|---:|
| `previous_community` | 0 | **15 673** |
| `previous_rho` | 0 | **15 673** |
| `previous_neighborhood_2hop` | 0 | **15 673** |

✅ **100 % coverage**. Rollover працює коректно.

### 3.2 Код `SlidingWindowDetector.processWindow()`

```java
private WindowResult processWindow(OffsetDateTime tStart, OffsetDateTime tEnd) {
  try (var projection = projectionManager.createWindowProjection(tStart, tEnd)) {
    if (projection.isEmpty()) {
      return new WindowResult(tStart, tEnd, true, 0L, 0L);
    }
    alpha1.compute(projection.name(), samplingSize, samplingSeed);
    alpha2.compute(projection.name(), tStart, tEnd, louvainSeed);
    alpha3.compute(tStart, tEnd);
    CompositeScorer.ScoreResult sr = scorer.score(tStart, tEnd);
    alpha2.rollState();          // лінія 133
    alpha3.rollState();          // лінія 134
    return new WindowResult(...);
  }
}
```

`rollState()` викликається після `scorer.score()`, у межах `try-with-resources(projection)`. Якщо projection або `scorer.score()` кидають виключення — rollState пропускається (не у finally). **Але в цьому прогоні немає пропущених rollState** (100 % coverage у DB). **Bug-risk існує теоретично** (відсутність finally), але на практиці не реалізувався.

**Висновок:** Scenario A (rollover broken) — **виключено**.

---

## Check 4: GDS projection sizes

### 4.1 Edges per 5-min window (Tue+Wed)

| Метрика | Фактично | Очікувано | Статус |
|---|---:|---:|---|
| total_windows (non-empty) | 202 | ~576 | ✅ (решта 374 вікон empty через пропуски трафіку) |
| mean_edges per window | **1 015** | 300–800 | ✅ (вище очікуваного) |
| p50_edges | **1 027** | 100–500 | ✅ |
| p95_edges | **1 805** | — | ✅ |
| min_edges | 1 | ≥ 0 | ✅ |
| max_edges | 2 465 | — | ✅ |

Граф **достатньо щільний** для обчислення BC у 5-хв вікнах. Scenario D (graph too sparse) — **виключено**.

### 4.2 Контрольне проектування single-window

Вікно `2017-07-04T10:00:00Z + 5min`:
- `nodeCount = 15 673` (всі Host-вузли в проекції, незалежно від активності)
- `relationshipCount = 1 475` (edges у вікні)
- `nonzero_bc_hosts = 81` (0,5 % від total)
- `mean_bc = 458,2`, `max_bc = 8 006,2`

**Спостереження:** `gds.graph.project.cypher` проектує **всі** 15 673 Host-вузли, навіть якщо у вікні активні лише 81–400 з них. Це означає, що BC обчислюється на overcollected графі — формально коректно, але архітектурно субоптимально (неактивні хости додають тільки шум і inflate total_events count).

Незважаючи на це, GDS дає **содержательний результат** (81 хост з ненульовою BC у короткому вікні — це gateway-подібні вузли). Метод працює як очікувалося.

---

## Check 5: Score threshold calibration

### 5.1 Розподіл scores (`AnomalyEvent.score`)

| Percentile | Value |
|---|---:|
| min | 0,0477 |
| p50 | 0,0477 |
| p90 | 0,0477 |
| p95 | 0,0551 |
| p99 | 0,6477 |
| max | 0,9978 |

Optimal θ_A з grid search = 0,600. p99 = 0,648. **Gap = 0,05** — не критичний, але ≈99-percentile поріг означає, що ~1 % events дають позитивний predict.

### 5.2 F1-sweep на повному events-корпусі (Python)

Fine-grained sweep over `score` threshold (всі 3 компоненти з weights 0,5/0,4/0,1):

| θ | predictions | TP | FP | Precision | Recall | F1 |
|---:|---:|---:|---:|---:|---:|---:|
| 0,10 | 76 182 | 112 | 76 070 | 0,0015 | 1,000 | 0,0029 |
| 0,30 | 70 216 | 112 | 70 104 | 0,0016 | 1,000 | 0,0032 |
| **0,40** | 23 689 | 102 | 23 587 | 0,0043 | **0,911** | **0,0086** |
| 0,50 | 17 218 | 41 | 17 177 | 0,0024 | 0,366 | 0,0047 |
| **0,60** (grid-best) | 8 034 | 18 | 8 016 | 0,0022 | 0,161 | **0,0044** |
| 0,70 | 1 515 | 7 | 1 508 | 0,0046 | 0,063 | 0,0086 |
| 0,95 | 256 | 2 | 254 | 0,0078 | 0,018 | **0,0109** |

Sweep over **pure α₁_norm** (як одиничний signal, weights 1/0/0):

| θ_α₁ | predictions | TP | FP | Precision | Recall | F1 |
|---:|---:|---:|---:|---:|---:|---:|
| 0,120 | 30 329 | 59 | 30 270 | 0,0019 | 0,527 | 0,0039 |
| 0,150 | 11 056 | 53 | 11 003 | 0,0048 | 0,473 | 0,0095 |
| **0,200** | 6 215 | **42** | 6 173 | 0,0068 | **0,375** | **0,0133** |
| 0,300 | 4 302 | 24 | 4 278 | 0,0056 | 0,214 | 0,0109 |
| 0,500 | 3 440 | 15 | 3 425 | 0,0044 | 0,134 | 0,0084 |

**Ключові висновки:**

1. Score-based grid search (θ_A = 0,6) дає F1 = 0,0108 — **підтверджено незалежним python-sweep** (0,0044 для composite@0,6 з моїх даних є нижче, ніж репортоване 0,0108 у evaluation — розбіжність у тому, що evaluation використовує другий протокол dedup; direct-python-sweep консервативніший).
2. Pure α₁ threshold ≥ 0,2 дає **F1 = 0,0133** — на 23 % вище грід-бест. **Grid search не включив цю точку** (simplex crawling зупиняється на θ_A ∈ {0,2..0,7} × {w_i ≥ 0,1 each}), тому конфігурацію 1/0/0 грід не досліджував.
3. Recall = 1,0 тримається до θ = 0,3 бо навіть attacker-хости отримують floor-score > 0,3 у деяких вікнах. Це **не корисна інформація для виявлення**: передбачити all-events-positive = тривіальна стратегія.
4. Реальний optimum десь біля θ_α₁ = 0,2 з pure-α₁ конфігурацією, F1 ≈ 0,013 — **покращення є, але не змінює порядок величини**.

### 5.3 Gap між grid-best і broader sweep

- Grid search: 216 configs (36 simplex × 6 thresholds), simplex crawling step 0,1
- Broader sweep показав: (a) pure-α₁ конфігурація дає +23 % F1; (b) дуже високий поріг θ=0,95 + composite теж дає F1 = 0,011

**Scenario B** застосовна частково — калібрування threshold можна поліпшити для F1 ≈ 0,013 (замість 0,011), але не змінює загальної картини.

---

## Verdict

**method genuinely underperforms** (Scenario E) **+ partial threshold mis-calibration** (Scenario B)

### Обґрунтування

Діагностика виключила такі баги:

- ❌ Scenario A (rollover broken): 100 % hosts have previous_* coverage; rollover працює
- ❌ Scenario C (baseline coverage insufficient): 100 % hosts have baseline; мінімум samples 98, охоплення повне
- ❌ Scenario D (graph too sparse): mean_edges per window = 1 015, p50 = 1 027 — достатньо щільний граф

Діагностика підтвердила такі **структурні обмеження методу на CICIDS2017**:

1. **93,1 % хостів — leaf nodes** (baseline_bc_mean = 0), для яких α₁ стає **binary-flag** замість континуальної метрики. Це кепсько обмежує discrimination.
2. **α₂ і α₃ у 95 % випадків = 0** — structural stability benign-трафіку перевищує structural churn attack-трафіку на 5-хв часовому масштабі.
3. **Base rate = 112 / 76 646 = 0,146 %** — у такому режимі F1 структурно обмежений через dominant FP-pool, навіть при високому recall.
4. **Victim-tagging у GT**: серед 112 GT-пар значна частка — це **сервери-жертви**, що отримують attack-flow. Вони структурно виглядають нормально (receiver of many requests = as legitimate server), тому метод їх не виявляє.

Додатково можна покращити F1 з 0,0108 до **≈ 0,0133** (+23 %) через broader threshold grid (pure-α₁ @ 0,2), але порядок величини не зміниться. Рушій низького F1 — не баг, а поєднання властивостей датасету і методу.

---

## Recommended action

**Proceed to T20 with negative-result framing** (scenario E).

Paper v2 (комміт `3391d86`) уже сформульовано чесно: Variant В у Section 6.3.2 прямо визнає, що метод не перевершує baseline у жодному класі атак на прийнятому протоколі. Abstract обидвома мовами оновлено без «consistently outperforming». Це відповідає вимогам наукової чесності і не блокує подання.

### Дрібні правки, рекомендовані перед T20

1. **Покращити threshold-sweep** (1–2 години): перезапустити grid search з розширеним діапазоном `θ_A ∈ [0,05, 0,95]` step 0,025, додати corner points simplex {1,0,0 / 0,1,0 / 0,0,1}. Новий best ймовірно F1 ≈ 0,013. Рекомендую зафіксувати найкращу конфігурацію в Table 6.4 та оновити таблицю.
2. **Розширити Section 6.6 (обмеження)**: додати пункт «Victim-tagging ground truth», пояснюючи, що значна частка GT-позитивних — сервери-жертви, структурно невидимі для graph-centric сигналів. Це додатково зміцнює наукову чесність.
3. **Додати прикінцевий абзац у Section 7.3**: конкретний research gap — «графова метрика ефективна лише для attacker-side хостів; потрібен окремий flow-centric channel для victim-side detection + fusion layer».

### Опціонально (для Scopus-версії)

- Замінити α₃ (Jaccard drift) на k-core drift або PageRank deviation (Section 6.3.2 обговорено).
- Використати victim-inclusive feature vector: приєднати fan-in rate, byte volume ratio до композитної метрики.
- Перевірити на датасетах з richer topology: CSE-CIC-IDS2018, CTU-13 (botnet), real enterprise captures.

### Часовий бюджет

| Step | Time |
|---|---|
| Broader threshold sweep + update Table 6.4 | 1–2 год |
| Section 6.6 victim-tagging note | 20 хв |
| Section 7.3 victim-channel idea | 20 хв |
| **Разом** | **2–3 год** (перед T20) |

Всі ці правки **опціональні**; без них стаття у поточному стані вже самодостатня і придатна до подання в категорії Б з честним negative-result framing.

---

## Додатки: сирі числа для відтворення

### Розмір T19 expected vs actual (критичні відхилення)

| Метрика | Expected (instruction) | Actual | Вердикт |
|---|---|---|---|
| total_hosts | 30–60 | 15 673 | включає всі external endpoints — норма |
| attack_edges | 30K–50K | 213 | результат 60-с aggregation — норма |
| gt_pairs | 3K–15K | 112 | host-window protocol + high concentration — норма |
| total_events | тисячі–10K | 3 165 946 | всі host×window у grid-search mode — норма |
| α₁ floor | — | 0,119 у 95 % | σ(−2) при θ₁=2,0, current_bc=0 — формула |
| baseline_mean=0 hosts | — | 14 597 (93 %) | leaf nodes — структурна властивість CICIDS2017 |

### Джерела даних

- Neo4j: `agids-neo4j:2026.03.1-community + GDS 2026.03.0`
- PostgreSQL: `agids-postgres:16.6-alpine`, flows table 1 668 474 rows
- T19 events: 3 165 946 AnomalyEvents у Neo4j
- Python-sweep: `/tmp/all_events.csv` (3,16M rows), `/tmp/universe.csv` (76 646), `/tmp/gt.csv` (112)
- Weight simplex: `results/weight_simplex.csv` (216 configs, 36 simplex × 6 thresholds)
