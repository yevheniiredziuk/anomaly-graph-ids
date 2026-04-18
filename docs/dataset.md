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

## Download status (T03)

**Станом на 2026-04-18 автоматичне завантаження з CIC-серверів недоступне.**
URL `http://cicresearch.ca/CICDataset/CIC-IDS-2017/Dataset/CIC-IDS-2017/CSVs/MachineLearningCSV.zip`
та його дзеркало `http://205.174.165.80/...` тепер відповідають HTTP 301/302 на
UNB landing-сторінку `https://www.unb.ca/cic/datasets/index.html`, де потрібно
заповнити форму для отримання актуального посилання. Це очікувано — спец T03
прямо передбачає такий сценарій.

### Manual download — required

1. Перейти на https://www.unb.ca/cic/datasets/ids-2017.html
2. Заповнити короткий request-form (ім'я, email, інституція, мета використання)
3. На email прийде посилання на `MachineLearningCSV.zip`
4. Зберегти файл як `data/raw/cicids2017/MachineLearningCSV.zip`
5. Виконати `./scripts/download_cicids2017.sh` — скрипт побачить наявний zip і
   лише розпакує його
6. Виконати `./scripts/run_prepare.sh`

### Alternative mirrors

- **IEEE DataPort** — https://ieee-dataport.org/documents/cicids2017 (потрібен
  IEEE-акаунт, безкоштовна реєстрація)
- **Kaggle** — https://www.kaggle.com/datasets/cicdataset/cicids2017 (копія
  MachineLearningCSV, поле `Timestamp` може бути у дещо іншому форматі — але
  наш парсер `dayfirst=True, errors="coerce"` це покриває)

## Pipeline validation

Preprocessing-pipeline (`scripts/prepare_dataset.py`) провалідовано end-to-end
на синтетичній вибірці з повною 79-колонковою CICIDS2017-схемою
(408/458/488 рядків для Mon/Tue/Wed; відтворено leading-space артефакт імен
колонок, дублікати, NaN/Inf значення, формат timestamp `d/m/yyyy H:MM`).
Усі стадії (`normalize_columns`, `drop_duplicates`, `drop_invalid_numeric`,
`parse_timestamp`, `aggregate_flows_to_edges`) відпрацювали без помилок.
Синтетичні артефакти не комітяться (gitignored), результат валідації:
Mon 400 / Tue 450 / Wed 480 cleaned rows, 1021 аґрегованих ребер з очікуваним
розподілом міток (BENIGN ~52 %, FTP-/SSH-Patator, DoS-варіанти, Heartbleed).

## Actual preprocessing statistics

> _Заповнюється автором статті після manual-download реального датасету та
> запуску повного pipeline. Числа потрібні для Section 6.1._

| Метрика | Значення |
|---|---|
| Raw flows (Mon+Tue+Wed) | _TBD_ |
| Removed duplicates | _TBD_ |
| Removed NaN/Inf rows | _TBD_ |
| Cleaned flows | _TBD_ |
| Aggregated edges (bucket=60s) | _TBD_ |
| Unique source IPs | _TBD_ |
| Unique destination IPs | _TBD_ |
| Label distribution | _TBD_ |
| Runtime (pandas, single thread) | _TBD_ |
