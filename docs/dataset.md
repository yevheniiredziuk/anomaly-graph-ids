# CICIDS2017 dataset — опис та обмеження

## Джерело

Canadian Institute for Cybersecurity, University of New Brunswick.
Офіційна сторінка: https://www.unb.ca/cic/datasets/ids-2017.html

Цитується як:
Sharafaldin, I., Habibi Lashkari, A., Ghorbani, A. A. (2018). Toward Generating
a New Intrusion Detection Dataset and Intrusion Traffic Characterization. ICISSP.

## Використана підмножина

Для експерименту використано 3 дні трафіку з 5 доступних:

| День | Використання | Типи подій |
|---|---|---|
| Monday | Baseline (чистий трафік) | BENIGN only |
| Tuesday | Тест, атаки зі структурним слідом | FTP-Patator, SSH-Patator |
| Wednesday | Тест, DoS-атаки | DoS Hulk / GoldenEye / slowloris / slowhttptest, Heartbleed |

Дні Thursday і Friday (Web Attacks, Infiltration, Bot, Port Scan, DDoS)
**виключені зі scope даної статті**. Обґрунтування:

1. Port Scan має очевидний структурний слід і був би «легким» випадком;
   не дає достатньо інформації про складні сценарії.
2. Web Attacks / Infiltration у цьому датасеті мають відомі проблеми з мітками
   (Engelen et al., 2021).
3. DDoS — коріння проблеми надто очевидне; метод буде працювати тривіально.

Залишені дні Tuesday (Brute Force) та Wednesday (slow DoS) — це найбільш
інформативні підмножини для перевірки виявлення **поведінкових структурних** аномалій.

## Відомі обмеження датасету

Дане джерело має задокументовані якісні проблеми. Для повного викладу див.
Engelen et al. (2021) «Troubleshooting an Intrusion Detection Dataset: the
CICIDS2017 Case Study», IEEE SPW. Коротко:

1. **Дублікати.** Оригінальний датасет містить 308 181 повністю дубльованих
   записів. Наш preprocessing видаляє їх на етапі `drop_duplicates`.

2. **NaN/Infinity у числових колонках.** CICFlowMeter (інструмент генерації
   flows) у певних edge cases (дуже короткі потоки, нульові тривалості)
   виробляє математично невалідні значення. Видаляються на етапі
   `drop_invalid_numeric`.

3. **Whitespace у заголовках колонок.** Відомий дефект CICFlowMeter —
   виправляється на етапі `normalize_columns`.

4. **Неоднозначність міток на рівні flow.** Деякі записи мають attack-мітку
   лише тому, що їх згенеровано з attacker IP, хоча сам flow може бути
   benign (background noise). Ми свідомо приймаємо цю неоднозначність —
   вона імітує реальні умови, де ground truth також неточна.

5. **Обмежена репрезентативність.** Датасет збирався в ізольованому лабораторному
   середовищі у 2017 році. Узагальнення результатів на сучасний enterprise-трафік
   обмежене. Це обмеження буде обговорене в секції 6.6 статті.

## Агрегація у «супер-ребра»

Сирі flows агрегуються у мультиграф-ребра за формулою з Section 4.2 статті:

    key = (src_ip, dst_ip, protocol, floor(t_start / Δt_a))    де Δt_a = 60 сек

Для кожного ключа аккумулюємо bytes/packets, беремо min/max timestamp.

Мітка super-ребра визначається правилом: якщо хоча б один flow у bucket'і є
атакою — ребро отримує відповідну attack-мітку; інакше BENIGN. Це консервативна
стратегія (прихиляємося до класифікації атаки, не упускаємо її).

## Python environment

Preprocessing-скрипт створено для Python 3.9+. Pinned-версії залежностей у
`scripts/requirements.txt`:

- `pandas==2.2.3`
- `numpy==1.26.4` (останній 1.x; сумісний з Python 3.9–3.12)
- `pyarrow==17.0.0`

Якщо запускаєте на Python 3.10+ — можна оновити до `numpy==2.1.3` і
`pyarrow==18.1.0` без змін коду.

## Очікувана preprocessing-статистика

На 3 днях (Mon+Tue+Wed) очікується (приблизно):
- Сирих flows: ~1.7 млн
- Після очищення: ~1.4 млн
- Після агрегації (bucket=60s): ~200–300 тис. ребер
- Унікальних хостів: ~20–30

## Варіант датасету — критично

CIC-IDS2017 розповсюджується у двох основних CSV-варіантах:

| Архів | Внутрішня папка | Поля Source IP, Dest IP, Timestamp, Flow ID? | Придатний для графів? |
|---|---|---|---|
| `GeneratedLabelledFlows.zip` (~430 MB) | `TrafficLabelling_/` | **Так** | **Так — використовуємо** |
| `MachineLearningCSV.zip` (~240 MB) | `MachineLearningCVE/` | Ні (тільки 79 фіч + Label) | Ні — для ML-класифікації |

**Ми використовуємо `GeneratedLabelledFlows.zip`**, бо тільки він зберігає
поля, необхідні для побудови графа `(src_host) → (dst_host)`.

Після розпакування скрипт `scripts/download_cicids2017.sh` автоматично переносить
CSV з внутрішньої папки (назва якої може варіюватися між релізами — `TrafficLabelling_`,
`GeneratedLabelledFlows`, або просто root архіву) у канонічне місце
`data/raw/cicids2017/flows/`.

## Download status (T03)

**Станом на 2026-04-18 автоматичне завантаження з CIC-серверів недоступне.**
URL `http://cicresearch.ca/CICDataset/CIC-IDS-2017/Dataset/CIC-IDS-2017/CSVs/*`
та його дзеркало `http://205.174.165.80/...` відповідають HTTP 301/302 на
UNB landing-сторінку `https://www.unb.ca/cic/datasets/index.html`, де потрібно
заповнити форму для отримання актуального посилання.

### Manual download — required

1. Перейти на https://www.unb.ca/cic/datasets/ids-2017.html
2. Заповнити короткий request-form (ім'я, email, інституція, мета використання)
3. У формі **явно вказати `GeneratedLabelledFlows.zip`** — не `MachineLearningCSV.zip`
4. На email прийде посилання; завантажити файл
5. Зберегти як `data/raw/cicids2017/GeneratedLabelledFlows.zip`
6. Виконати `./scripts/download_cicids2017.sh` — скрипт валідує, розпакує і
   нормалізує шляхи; fail-fast якщо виявить неправильний варіант (без Source IP)
7. Виконати `./scripts/run_prepare.sh`

### Alternative mirrors

- **IEEE DataPort** — https://ieee-dataport.org/documents/cicids2017 (потрібен
  IEEE-акаунт, безкоштовна реєстрація). Шукайте "GeneratedLabelledFlows" або
  "TrafficLabelling" CSVs, **не** MachineLearningCSV.
- **Kaggle** — https://www.kaggle.com/datasets/cicdataset/cicids2017 та
  https://www.kaggle.com/datasets/cicdataset/cicids2017 (перевірте, що архів
  має повний набір колонок, а не ML-feature-only).

## Actual preprocessing statistics

> Виконано на повному датасеті `GeneratedLabelledFlows.zip` (2026-04-18).
> Числа для Section 6.1 статті.

### Зведення

| Метрика | Mon | Tue | Wed | Mon+Tue+Wed |
|---|---:|---:|---:|---:|
| Raw flows | 529 918 | 445 909 | 692 703 | **1 668 530** |
| Duplicates removed | 34 | 4 | 18 | **56** |
| NaN/Inf rows removed | 0 | 0 | 0 | **0** |
| Cleaned flows | 529 884 | 445 905 | 692 685 | **1 668 474** |
| Aggregated edges (Δt=60 s) | — | — | — | **329 076** |
| Reduction ratio (flows→edges) | — | — | — | **5.07×** |

**Примітка.** Оригінальний датасет (згідно Engelen et al., 2021) містить
308 181 дубльованих записів, але ця цифра охоплює всі 5 днів + cross-file
збіги. На нашій підмножині Mon+Tue+Wed з-within-file-збігами лишається
тільки 56 рядків — решта дублікатів розподілена між іншими днями.

Рядків з NaN/Infinity у числових колонках після відсіювання зайвих фіч
(`KEEP_COLUMNS` залишає 23 з 85 колонок, серед них немає Flow Bytes/s і
Flow Packets/s — саме в них історично Engelen фіксував Inf) — 0. Сирі
колонки з Inf-значеннями у датасеті присутні, але нам вони не потрібні
для побудови графа.

### Унікальні сутності графа

| Метрика | Значення |
|---|---:|
| Unique source IPs | 13 788 |
| Unique destination IPs | 15 660 |
| Unique hosts (union) | **15 673** |
| Protocols (IANA numbers) | 0 (HOPOPT), 6 (TCP), 17 (UDP) |
| Time range | 2017-07-03 01:00:01 → 2017-07-05 12:59:00 |
| Total transferred bytes (fwd+bwd) | 31.9 GB |

> Кількість хостів (15 673) значно більша за «20–30» в оригінальній
> специфікації T03 — причина в тому, що датасет містить реальний
> background-трафік з зовнішніми IP (scanning, DNS, CDN тощо), а не лише
> testbed-машини з офіційного CICIDS2017 network diagram. Для експериментів
> це не проблема: graph-based детекція зацікавлена саме у структурних
> аномаліях «зовнішні → локальні», які й генерують такий розподіл.

### Розподіл міток

**На рівні flow (raw/cleaned):**

| Day | BENIGN | Attack labels |
|---|---:|---|
| Monday | 529 884 (100 %) | — |
| Tuesday | 432 070 (96.9 %) | FTP-Patator 7 938 · SSH-Patator 5 897 |
| Wednesday | 440 015 (63.5 %) | DoS Hulk 231 071 · DoS GoldenEye 10 293 · DoS slowloris 5 796 · DoS Slowhttptest 5 499 · Heartbleed 11 |

**На рівні aggregated edge (Δt=60 s, вхід до Neo4j):**

| Label | Edges | % |
|---|---:|---:|
| BENIGN | 328 863 | 99.94 % |
| FTP-Patator | 64 | 0.019 % |
| SSH-Patator | 63 | 0.019 % |
| DoS slowloris | 27 | 0.008 % |
| DoS Hulk | 21 | 0.006 % |
| DoS Slowhttptest | 19 | 0.006 % |
| Heartbleed | 11 | 0.003 % |
| DoS GoldenEye | 8 | 0.002 % |
| **Total** | **329 076** | 100 % |

**Чому attack-ребер так мало** попри сотні тисяч attack-flows на flow-level:
атаки походять з одного attacker-IP на один-два victim-IP — усі ці flows
колапсують у дуже малу кількість кортежів `(src, dst, proto, bucket_60s)`.
Benign-трафік натомість має на порядки більше унікальних комбінацій.
Це **бажана властивість**: супер-ребра природно компактують повторювані
сигнатури атак, зберігаючи структурну інформацію, яка й потрібна для
graph-based детекції. Для оцінки precision/recall ми пізніше рахуємо
метрики і на flow-level, і на edge-level (Section 6.3).

### Runtime

| Стадія | Час | Примітки |
|---|---:|---|
| Load + clean (3 файли) | ~25 с | pandas single-thread, `encoding="latin-1"` |
| Aggregation (groupby 1.67M rows) | ~20 с | pandas groupby + custom label-resolution |
| Save (parquet + CSV для Neo4j) | ~3 с | pyarrow engine |
| **End-to-end** | **43 с** | macOS M-series, Python 3.9.6, pandas 2.2.3 |

### Вихідні артефакти

| Файл | Розмір | Призначення |
|---|---:|---|
| `data/cleaned/{monday,tuesday,wednesday}.parquet` | 44 MB сумарно (15/13/16) | per-day cleaned flows |
| `data/cleaned/edges_aggregated.parquet` | 5.9 MB | агреговані ребра (parquet) |
| `data/cleaned/flows_for_postgres.csv` | 202 MB | non-aggregated flows для PostgreSQL baseline (T04) |
| `data/neo4j-import/cicids2017_mon_tue_wed.csv` | 39 MB | вхід для Neo4j import (T07) |

## PostgreSQL side (T04)

Повні сирі (неагреговані) flows завантажуються у партиційовану таблицю
`flows` через `\COPY`. Скрипт [`baseline/sql/load/load-flows.sh`](../baseline/sql/load/load-flows.sh):

| Метрика | Значення |
|---|---:|
| COPY throughput | ~315 000 rows/sec |
| COPY runtime (1.67 M flows) | ~5 с |
| End-to-end runtime (drop indexes → COPY → recreate → populate hosts) | ~7 с |
| `flows_mon` | 529 884 рядків · 122 MB |
| `flows_tue` | 445 905 рядків · 96 MB |
| `flows_wed` | 692 685 рядків · 150 MB |
| `flows_default` | **0 рядків · 48 kB** (усі рядки розпартиціоновано коректно) |
| `flows` total | 368 MB (heap + indexes) |
| `hosts` | 15 673 рядків · 544 kB |
| DB total | 378 MB |

Label distribution у PostgreSQL повністю збігається з post-cleanup числами з T03
(BENIGN 1 401 969, DoS Hulk 231 071, DoS GoldenEye 10 293, FTP-Patator 7 938,
SSH-Patator 5 897, DoS slowloris 5 796, DoS Slowhttptest 5 499, Heartbleed 11).

**Partition pruning verified** (query №5 в `baseline-queries.sql`):
`EXPLAIN` для `WHERE t_start BETWEEN '2017-07-04 09:00' AND '10:00'` показує
`Index Only Scan ... on flows_tue` — сканується тільки Tuesday-партиція,
без дотику до `flows_mon`/`flows_wed`. Час виконання ~13 ms для 89 694 рядків.

### Java ETL (T06)

Java-альтернатива до `load-flows.sh` через `org.postgresql.copy.CopyManager`
(модуль `etl`, класи `FlowsCopyLoader` + `HostsPopulator`). Потрібна для
чесного benchmark-порівняння Java→Neo4j vs Java→PostgreSQL у Section 6.4
статті.

| Метрика | bash `load-flows.sh` (T04) | Java `FlowsCopyLoader` (T06) |
|---|---:|---:|
| COPY runtime (1.67 M rows) | ~5.3 с | **6.08 с** |
| COPY throughput | ~315 k rows/sec | **274 k rows/sec** |
| MB/sec | — | **33.2 MB/sec** |
| End-to-end (incl. drop/create indexes + ANALYZE) | ~7 с | **~10 с** |
| hosts populate | ~1 с (in-query) | **0.9 с** |

Java-варіант трохи повільніший через JVM + Spring Boot startup (~1.5 с) і
одну додаткову мережеву hop через HikariCP, але на тому ж порядку. Сам COPY
через `CopyManager` еквівалентний server-side `COPY` — різниця тільки у
driver wrapping. Вибір на користь Java — testability
([`FlowsCopyLoaderIT`](../etl/src/test/java/ua/mitit/ids/etl/postgres/FlowsCopyLoaderIT.java))
+ майбутній streaming mode з Kafka (Section 7.3 статті).

## Neo4j side (T07)

Агреговані ребра завантажуються у Neo4j через `UNWIND $edges` з batch-розміром
5 000 у межах кожної Bolt-транзакції. Реалізація — модуль `etl`, класи
[`Neo4jUnwindLoader`](../etl/src/main/java/ua/mitit/ids/etl/neo4j/Neo4jUnwindLoader.java)
+ [`CsvFlowReader`](../etl/src/main/java/ua/mitit/ids/etl/neo4j/CsvFlowReader.java)
(streaming-parser Apache Commons CSV).

### Load run (1.67M flows → 329 k aggregated edges → Neo4j)

| Метрика | Значення |
|---|---:|
| Edges loaded | **329 076** |
| Unique hosts (Host nodes) | **15 673** |
| Batches (size=5 000) | 66 |
| Load runtime | **16.85 с** |
| Throughput | **19 527 edges/sec** |
| Database на диску (`/data/databases/neo4j`) | 66 MB |
| Transaction log | 258 MB (до checkpoint'у; ефемерно) |
| Schema-level indexes (after V001+V002) | 9 |

**Per-label edge count** (повний збіг з T03 post-aggregation):

| Label | Edges |
|---|---:|
| BENIGN | 328 863 |
| FTP-Patator | 64 |
| SSH-Patator | 63 |
| DoS slowloris | 27 |
| DoS Hulk | 21 |
| DoS Slowhttptest | 19 |
| Heartbleed | 11 |
| DoS GoldenEye | 8 |

### Порівняння ETL: PostgreSQL vs Neo4j

| | PostgreSQL (T06) | Neo4j (T07) |
|---|---:|---:|
| Input | 1.67 M raw flows | 329 k aggregated edges |
| Loader | `\COPY` via `CopyManager` | `UNWIND` batch=5000 |
| Throughput | 275 k rows/sec | 19.5 k edges/sec |
| Per-row work | server-side CSV parse → tuple insert | MERGE Host × 2 + CREATE edge per element |
| Storage on disk | 368 MB | 66 MB (+258 MB tx log) |

~14× різниця у throughput — очікувана: Neo4j UNWIND робить принципово більше
роботи на ребро (двічі MERGE на Host з unique-constraint lookup + CREATE
edge з 9 properties), PostgreSQL COPY — це низькорівневий tuple insert у
heap. Обидва шляхи fit-for-purpose для свого обсягу: Section 6.4 статті
порівнює не самі ETL-швидкості, а швидкість детекції на вже завантажених
даних, що і є метою benchmark'у.
