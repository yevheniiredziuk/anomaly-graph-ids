# T20 proofreading & assembly report

Commit base: `9e5bae7` (fix-cycle)
Build date: 2026-04-19

---

## Phase A — automated proofreading results

### Pass 1 — placeholders & cross-refs

| Check | Result |
|---|---|
| `{{RESULT\|NEW\|TODO\|FIXME\|XXX\|WIP}}` у body (виключно author-notes) | ✅ 0 matches |
| Tables referenced vs defined | ✅ 3.1, 3.2, 5.1, 6.1–6.6 — всі в обох |
| Figures referenced vs defined | ✅ 4.1, 6.1, 6.2, 6.3, 6.3б — всі співпадають |
| Figure image files на диску | ✅ 6 PNG у `results/figures/` + 6 SVG |

**Примітка:** Figure 4.1 (архітектура конвеєра) має caption, але окремого PNG/SVG-файлу немає — **потребує створення автором** (рекомендую diagrams.net, PlantUML або draw.io).

### Pass 2 — numeric consistency

| Метрика | Abstract | SECTION_6 | SECTION_7 | Статус |
|---|:---:|:---:|:---:|:---:|
| Graph F1 best (pure α₁) | 0,013 | 0,0134 | 0,013 | ✅ consistent rounding |
| Graph F1 coarse-composite | 0,011 | 0,0108 | 0,011 | ✅ |
| Baseline F1 | 0,080 | 0,0797 | 0,080 | ✅ |
| Betweenness p95 | 74,6 мс | 74,55 мс (table) / 74,6 мс (narrative) | 74,6 мс | ✅ |
| Louvain p95 | 219,0 мс | 219,02 мс (table) / 219,0 мс (narrative) | 219,0 мс | ✅ |
| Notebook spec | — | M1 Pro, 16 GB, macOS 26.4.1 (Table 6.3 + Limit.3) | — | ✅ |

Українська кома як десятковий розділювач дотримана всюди, крім англомовної версії Abstract (крапка). Корректно.

### Pass 3 — red-flag phrases

| Фраза | Знайдено |
|---|---:|
| «як ми вже згадували», «як можна побачити», «це дуже важливо» | 0 |
| «слід зазначити», «на нашу думку», «в сучасних умовах» | 0 |
| «дуже швидко», «надзвичайно» | 0 |

Voice consistency: 100% passive voice у body (1 active-voice instance у author-notes SECTION_4.md:208 — не в opublication body). ✅

### Pass 4 — formula numbering

- SECTION_3: (3.1)–(3.12), всі 12 `\tag{}` присутні. ✅ No dupes.
- SECTION_4: (4.1)–(4.3), всі 3 `\tag{}` присутні. Згадки (4.4), (4.5) у прозі — це sub-section refs (не formula numbers). ✅
- SECTION_5: немає нумерованих формул (тільки код-лістинги). ✅
- Дублі `\tag{...}` у всіх файлах: 0.

### Pass 5 — tables + figures

| Елемент | Caption | Image link у .md | Image file на диску |
|---|:---:|:---:|:---:|
| Tab 3.1, 3.2, 5.1, 6.1–6.6 | ✅ | n/a (tables) | n/a |
| Fig 4.1 (архітектура) | ✅ | ❌ | ❌ **author action** |
| Fig 6.1 (ROC) | ✅ | ✅ (додано в T20) | ✅ |
| Fig 6.2 (window) | ✅ | ✅ (додано в T20) | ✅ |
| Fig 6.3 (simplex heatmap) | ✅ | ✅ (додано в T20) | ✅ |
| Fig 6.3б (sensitivity) | ✅ | ✅ (fix-cycle) | ✅ |
| Fig 6.4 (per-attack recall) | ✅ (додано в T20) | ✅ (додано в T20) | ✅ |
| Fig 6.5 (latency) | ✅ (додано в T20) | ✅ (додано в T20) | ✅ |

**T20 fixes applied:**
- Fig 6.1, 6.2: прибрано `*(плейсхолдер)*` суфікс у caption, додано markdown `![](...)` link.
- Fig 6.3: додано `![](...)` link.
- Fig 6.4, 6.5: додано повний caption + image link у відповідних місцях SECTION_6.

### Pass 6 — bibliography

| Метрика | Значення |
|---|---:|
| Max citation номер у тексті | [24] |
| UA REFERENCES.md entries | 39 |
| EN REFERENCES_EN.md entries | 29 |
| DOI entries (UA / EN) | 21 / 19 |
| Cyrillic leak в EN body | 2 chars (minor) |
| Gaps у cited numbers | [5], [11], [18], [21] не використовуються у тексті |

**Транслітерація імен (пер. КМУ №55):**
- Ільєнко → Ilienko ✅
- Ільїн → Ilin ✅
- Старинський → Starynskyi ✅

**Author action:** розглянути (1) renumber text-citations щоб закрити gaps [5, 11, 18, 21], (2) harmonize UA (39) vs EN (29) bibliography asymmetry.

### Pass 7 — read-through

Довжина параграфів: знайдено 1 параграф з 14 реченнями — це numbered list у SECTION_1, допустимо. Інших >8-речень параграфів немає. Статя послідовно passive-voice, стиль академічний.

---

## Phase Б — build artifacts

| File | Size | Notes |
|---|---:|---|
| `build/article_full.md` | 177 KB (~1 160 lines) | assembled from 9 .md sources |
| `build/Kolinets-AGIDS-v1.docx` | 1,2 MB | pandoc 3.9, gfm+tex_math_dollars, 6 embedded images |
| `build/figures/*.png` | 6 files | fig 6.1, 6.2, 6.3, 6.3b, 6.4, 6.5 |
| `build/assemble.sh` | 3,2 KB | idempotent rebuild script |
| `build/pandoc.log` | — | pandoc output (for debugging) |

**Word count:** 14 259 слів (~158 K знаків без пробілів). КОНТ типовий діапазон — 4 000–6 000 слів (22–28 тис. знаків). **Стаття у ~6× над цільовим обсягом**. Це свідоме рішення (чернетка повний корпус → compress), але для подачі потрібне суттєве скорочення — див. author checklist нижче.

---

## Що Claude Code НЕ зробив (потребує ручного втручання автора)

### 1. Контент: скорочення до КОНТ-обсягу (критично)

Стаття ~158K знаків проти цільових 22–28K (≈ −80 %). Пропозиції для скорочення:
- **SECTION_2** (огляд, зараз ~108 рядків) — скоротити до 1,5–2 сторінки, залишити ключові survey-посилання
- **SECTION_3** (формальна модель, 222 рядки) — прибрати формули 3.6–3.8, 3.10–3.12 у додаток або згорнути; залишити 3.1–3.5 + 3.9 для ядра
- **SECTION_5** (реалізація, 273 рядки) — прибрати половину код-лістингів, залишити 2–3 ключові
- **SECTION_6** (318 рядків) — не скорочувати, це ядро; але прибрати Table 6.1 (preprocessing stats) якщо тиск надто сильний
- Author-notes у кожному файлі (після `## Примітки для автора`) — **вже видалено** build-скриптом, у article_full.md їх немає

### 2. Метадані

Створіть `build/article_frontmatter.yaml` з такими даними:
```yaml
---
title: "Метод виявлення аномальної мережевої активності на основі графових алгоритмів у Neo4j з використанням Graph Data Science"
author:
    - name: "Колінець Євгеній Володимирович"
      orcid: "XXXX-XXXX-XXXX-XXXX"       # ← заповніть
      email: "your@email.ua"              # ← заповніть
      affiliation: "Міжнародний інститут інформаційних технологій"
date: "2026"
lang: uk
---
```

Після заповнення — вставте як frontmatter на початок `article_full.md` і перезапустіть `assemble.sh`.

### 3. Figure 4.1 — архітектурна діаграма

`SECTION_4.md:17` має caption "**Рисунок 4.1 — Архітектура обчислювального конвеєра**", але файлу немає. Створіть у draw.io / diagrams.net / PlantUML як чотирьох-етапний конвеєр (ETL → baseline → detector → scoring), експортуйте `fig_4_1_pipeline.png` у `results/figures/`, додайте markdown `![](figures/fig_4_1_pipeline.png)` перед caption.

### 4. Citation gaps

[5], [11], [18], [21] не використовуються у тексті. Варіанти:
- Renumber (recompact) — вигідніше для рецензента
- Додати ці джерела у текст (якщо доречно)
- Або зберегти (acceptable, але subptimal)

### 5. UA vs EN bibliography asymmetry

UA має 39 entries, EN — 29. Гармонізуйте до одного списку; стандартна практика — EN список дзеркалить UA (переклади транслітер., англомовні посилання копіюються as-is).

### 6. Вимоги КОНТ

Перевірте на https://csecurity.kubg.edu.ua/index.php/journal/about/submissions:
- Template .docx (якщо є — перекопіюйте контент у template)
- Шрифт / інтервал / поля
- ДСТУ 8302:2015 vs ГОСТ у REFERENCES — перевірити на відсутність «. —» комбінацій (повинно бути «.»). Вже перевірено: у нашому REFERENCES.md ДСТУ 8302 стиль.
- Single-blind vs double-blind review — якщо double-blind, видалити author info з docx перед submission

### 7. Ручні правки docx у Word/LibreOffice

Pandoc зробив базову конвертацію. Перед submission перевірте у Word:
- Нумерація розділів (1., 2., ...) — може бути не автогенерована
- Resize рисунків (pandoc вставляє full width)
- Рамки таблиць (pandoc-generated таблиці мають бути з Table Design → All Borders)
- Формули — `$\alpha_1$` має рендеритись як $\alpha_1$, а не текстом. Перевірити візуально.
- Monospace-шрифт для code blocks (Courier New 10–11pt)

### 8. Final checks перед submission

- [ ] Word count у цільовому діапазоні КОНТ (22–28K знаків)
- [ ] Метадані заповнені (title, author, ORCID, email, affiliation)
- [ ] Figure 4.1 створено і додано
- [ ] Всі формули читабельні
- [ ] Рамки на таблицях
- [ ] Backup docx на Dropbox/GDrive/USB
- [ ] PDF-preview збережено для власного архіву

---

## Rebuild from source

Якщо ви внесли зміни у будь-який `paper/*.md` — перезапустіть збірку:

```bash
cd anomaly-graph-ids/paper
bash build/assemble.sh
# Output: build/Kolinets-AGIDS-v1.docx
```

Скрипт ідемпотентний: видаляє і перестворює `build/` з нуля за <5 секунд.
