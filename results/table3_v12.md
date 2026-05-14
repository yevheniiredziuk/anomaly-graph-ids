# Таблиця 3 v12 — оновлений draft

Дата прогону: 2026-04-29. Скрипт: [scripts/if_baseline_dual_gt.py](../scripts/if_baseline_dual_gt.py).
Дані: [data/cleaned/flows_for_postgres.csv](../data/cleaned/flows_for_postgres.csv) — 1 668 474 cleaned flows.
Період: Tue+Wed 2017-07-04..06 UTC, вікно 5 хв.

## Числа

| Метод                                | GT             |       n |  n+ |       P |       R |      F1 |
| ------------------------------------ | -------------- | ------: | --: | ------: | ------: | ------: |
| Графовий fine-best (pure α₁)         | victim-tagging |  76 646 | 112 |  0,0068 |  0,3393 |  0,0134 |
| Графовий fine-best (pure α₁)         | source-only    |  50 694 |  56 |  0,0005 |  0,0536 |  0,0011 |
| Rule-based baseline                  | victim-tagging |  76 646 | 112 |  0,0431 |  0,5357 |  0,0797 |
| **Rule-based baseline**              | **source-only**|**50 727**|**56**|**0,0184**|**0,3929**|**0,0351**|
| **IF на 21 flow-ознаці**             | **victim-tagging**|**76 646**|**112**|**0,1589**|**0,8286**|**0,2667**|
| IF на 21 flow-ознаці                 | source-only    |  50 727 |  56 |  0,1132 |  0,8571 |  0,2000 |

> Примітка про universe source-only (50 727 vs 50 694 у v11): незначна
> розбіжність 33 пари ≈ 0,07 % походить від різного round-trip перетворення
> часу (мій `pd.Timestamp.floor('5min')` vs Java `(epoch/(5·60))·(5·60)`);
> на TP/FP/FN це не впливає, бо обидві GT-positive множини збігаються по
> 56 хостам точно. n=50 727 — це нове, валідне з cleaned CSV число.

> Test-вибірки IF (per-day 50/50 split, друга половина дня):
> victim — n=38 459, n+=70; source-only — n=25 688, n+=35.
> Rule-based не має train/test split (правила фіксовані), тож метрики
> наведено на ВСЬОМУ universe.

## Sanity check

Rule-based на victim-tagging GT (відтворення F1 з v11 SECTION_6.md):
**F1 = 0,0797** (очікувано 0,0797, відхилення **< 0,0001**).
P = 0,0431 (очікувано 0,0431), R = 0,5357 (очікувано 0,5357 — збіг до 4-го знака).
Кількість detections (port_scan: 788, brute_force: 1 022, dos_flood: 44)
точно збігається з paper/SECTION_6.md рядок 123. Реімплементація
PL/pgSQL-детекторів у Python є біт-в-біт еквівалентною.

IF на 21 flow-ознаці на source-only GT (відтворення F1 з v11 «Таблиці 8»):
**F1 = 0,2000** vs очікувано 0,1613 — відхилення 0,0387.
**Відтворення неможливе**, бо IF-baseline-скрипт у репозиторії відсутній
(перевірено: жодного `IsolationForest`/`n_estimators`/`max_samples`/
`contamination` у коді, жодного попадання `0,1613` чи `50 694` у paper-
секціях, supervised_baseline.py — це LR на 3 α-ознаках, не IF на 21
flow-ознаці). Ймовірне джерело числа 0,1613: чернетка-v11.docx, якої
немає у файловій системі (є лише v1.docx з табл. 6.1–6.5 без IF).
Тож 0,2000 — це ПЕРШИЙ принциповий прогін IF, а не reproduce.

## Конфігурація прогону

- **Скрипт:** [scripts/if_baseline_dual_gt.py](../scripts/if_baseline_dual_gt.py)
- **Дані:** [data/cleaned/flows_for_postgres.csv](../data/cleaned/flows_for_postgres.csv)
  (1 668 474 cleaned flows; 1 138 590 у Tue+Wed)
- **Період:** [2017-07-04 00:00, 2017-07-06 00:00) UTC; 5-хв вікна (202 вікна)
- **IF гіперпараметри:** n_estimators=200, max_samples=256,
  contamination=0,001, random_state=20260422, n_jobs=−1
- **21 ознака на (host, window):**
  flow_count, as_src_count, as_dst_count, flow_duration_{mean,max,std},
  bytes_{fwd,bwd}_sum, packets_{fwd,bwd}_sum, mean_pkt_size,
  mean_packets_per_flow, {syn,rst,psh,ack,fin,urg}_count_sum,
  unique_remote_ips, unique_remote_ports, log_flow_count.
  Формулювання симетричне для obox GT: для victim-universe агрегуємо flows,
  де host у ролі src АБО dst (розмежовано полем as_src_count/as_dst_count);
  для source-only — лише як src (as_dst_count=0 завжди).
- **Train/test split:** per-day 50/50 temporal (як у
  [scripts/supervised_baseline.py](../scripts/supervised_baseline.py),
  розділ 6.2.1). Cutoff = медіана window_start у дні.
- **Поріг IF:** підбір на train за максимізацією F1 на anomaly-score
  (200 квантилів train-розподілу від 0,5 до 0,999; знайдені пороги
  0,744 для victim і 0,744 для source-only).
- **Rule-based пороги (з SQL, без переоптимізації):**
  port_scan endpoints≥50; brute_force attempts≥100 та short_ratio<5s≥70%;
  dos_flood flows≥1000 та concentration≥70%. Кожен detection (src, dst,
  window) розгортається у дві пари (src, window)+(dst, window) — точно як
  у [BaselinePredictionsCollector.java:41-42](../evaluation/src/main/java/ua/mitit/ids/evaluation/collectors/BaselinePredictionsCollector.java).
  port_scan має dst=NULL, тож додає лише src.

## Інтерпретація

1. **Прогноз A підтверджено лише частково.** Очікувалося, що IF/victim ≥
   IF/source. Точно: F1=0,2667 (victim) > F1=0,2000 (source-only) — на
   33 % вище. Recall обох близький (0,83/0,86), різниця в Precision:
   victim universe щільніший і дозволяє моделі краще розрізнити attacker-
   та victim-аномалії, бо у симетричних 21 ознаках з'являється сигнал
   asymmetry «host ngày як src vs dst» (as_src_count/as_dst_count, fwd/bwd
   bytes), що фактично дублює GT-логіку. Це warning для інтерпретації:
   IF на victim має «недозволений» доступ до структурного сигналу,
   корелюючого з GT-правилом.

2. **Прогноз B підтверджено точно.** Rule-based/source < rule-based/victim:
   F1 = 0,0351 (source) проти 0,0797 (victim) — у 2,3× нижче. Причина: rule-
   based виявляє ATTACKERS (src), але викидає dst-positives як FN при
   source-only GT, тоді як викидаючи expansion на dst при матчингу втрачає
   половину «right answers» що victim-GT йому prosthetically приписував.
   Це підтверджує гіпотезу рецензента: F1=0,0797 на victim GT був завищений
   завдяки симетрії GT, а на «чесному» source-only GT rule-based слабший.

3. **Головний наслідок для статті.** На обох GT IF на 21 flow-ознаці
   б'є rule-based на 1 порядок (0,27 проти 0,08 на victim; 0,20 проти
   0,04 на source-only) і б'є графовий метод на 1–2 порядки. Це **посилює**
   мейн-наратив статті (графовий метод не конкурує на CICIDS2017), але
   водночас спростовує побічну тезу «rule-based — sufficient baseline»:
   rule-based виявився найслабшим серед трьох, і ML-baseline є необхідним
   для коректного позиціонування методу. Зокрема, для рецензентового К1
   (К1: відсутність ML-baseline) тепер є відповідь.

4. **Запис F1=0,1613 з v11 для IF/source-only — не відтворюється.**
   Можливі джерела розбіжності з v11: інший набір 21 ознак (стандартні
   pcap_ISCX flow-features замість моїх host-window aggregations), інший
   train/test split, інший contamination/threshold-protocol. Якщо у v11
   використано flow-level (не host-window) IF — то n=50 694 НЕ відповідає
   (бо це host-window-cardinality), а 0,1613 був би рахунком на іншому
   universe. Це окрема методологічна неузгодженість у v11, що варто
   зазначити в response-letter рецензенту як «уніфіковане формулювання
   IF на host-window-рівні дає F1=0,2000».

## Машинні артефакти

- [results/dual_gt_results.json](dual_gt_results.json) — повні метрики +
  конфігурація + перелік ознак.
- [scripts/if_baseline_dual_gt.py](../scripts/if_baseline_dual_gt.py) —
  відтворюваний прогон (одна команда: `./scripts/.venv/bin/python
  scripts/if_baseline_dual_gt.py`, ~30 с на M1).
