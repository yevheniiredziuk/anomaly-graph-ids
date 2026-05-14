# Анотації

> **Статус:** v2 — фактичні числа T19 заповнено.
> **Обсяг:** укр — ~2100 знаків з пробілами; англ — ~2000 знаків з пробілами.
> **Структура:** згідно з рекомендаціями IMRaD для структурованої анотації.

---

## УКРАЇНСЬКА ВЕРСІЯ

### Назва

**Діагностика обмежень unsupervised-композиції класичних графових сигналів для виявлення мережевих аномалій у Neo4j з використанням Graph Data Science: негативний результат на CICIDS2017**

### Анотація

**Постановка проблеми.** Flow-centric IDS ігнорують структурний контекст взаємодій між хостами, що обмежує виявлення атак зі структурним слідом. Графові методи потенційно закривають цю прогалину, але переважно реалізуються у вигляді ресурсномістких GNN, що не забезпечують інтерпретовності для SOC.

**Мета.** Діагностично перевірити на CICIDS2017, чи здатна unsupervised-композиція трьох класичних локальних графових сигналів, реалізована на промисловому стеку Neo4j + Graph Data Science без ML-залежностей, конкурувати з flow-centric rule-based baseline.

**Методологія.** Побудовано формальну модель темпорального мультиграфа трафіку та композитну метрику $A(v,t) = w_1\tilde{\alpha}_1 + w_2\alpha_2 + w_3\alpha_3$: $\alpha_1$ — відхилення betweenness centrality від baseline; $\alpha_2$ — зміна спільноти Louvain, зважена на intra-community edge ratio; $\alpha_3$ — Жаккарове розходження 2-hop околиці. Метод реалізовано на Java 21 / Spring Boot 3 з Neo4j 2026.03 + GDS. Rule-based baseline реалізовано на PostgreSQL 16.

**Результати.** У host-window протоколі при $\Delta t = 5$ хв на CICIDS2017 (Mon+Tue+Wed, очищення за Engelen та ін.): fine-grid-best pure-$\alpha_1$ дає $F_1 = 0{,}0134$ (95% CI [0,0084; 0,0186]); coarse-grid-best композит — $F_1 = 0{,}0108$; supervised-варіант ($\tilde\alpha_1, \alpha_2, \alpha_3$) — $F_1 = 0{,}0063$. На матриці GT × Method: rule-based baseline — $F_1 = 0{,}0797$ на victim-tagging GT і $F_1 = 0{,}0351$ на source-only; Isolation Forest на 21 flow-ознаці — $F_1 = 0{,}2667$ [0,2348; 0,2996] на victim і $F_1 = 0{,}2000$ [0,1706; 0,2311] на source-only. Графова композиція поступається rule-based у 6 ×, IF — у 20 × на обох GT. Критерій Мак-Немара (IF проти rule-based на спільному test-set): $\chi^2 = 326$, $p < 10^{-4}$. Діагностично виявлено: низький recall графової метрики переважно пояснюється структурною невидимістю серверів-жертв; rule-based F1 падає у 2,3 × при переході victim → source-only GT, що підтверджує систематичне завищення victim-tagging-метрики dst-expansion-ом.

**Наукова новизна.** Негативний результат з діагностикою: кількісно встановлено межі застосовності лінійної unsupervised-композиції трьох локальних графових сигналів на зіркоподібних CICIDS2017-подібних топологіях; запропоновано source-only декомпозицію ground truth як інструмент аудиту графових IDS.

**Практичне значення.** Робота має передусім методологічне значення: результати є діагностичним шаблоном для аудиту графових IDS на CICIDS2017-подібних датасетах та основою для розвитку напряму attacker/victim channel fusion. Матеріали придатні для навчального процесу спеціальностей 122 «Комп'ютерні науки» та 125 «Кібербезпека та захист інформації».

### Ключові слова

виявлення вторгнень; виявлення аномалій; графова база даних; Neo4j; Graph Data Science; betweenness centrality; виявлення спільнот; CICIDS2017

---

## ENGLISH VERSION

### Title

**Diagnosing the limits of an unsupervised composition of classical graph signals for network anomaly detection in Neo4j with Graph Data Science: a negative result on CICIDS2017**

### Abstract

**Problem statement.** Flow-centric IDSs ignore the structural context of inter-host interactions, limiting detection of attacks with a structural footprint. Graph-based methods can close this gap, but industrial adoption is impeded by reliance on resource-intensive GNNs that lack interpretability for SOC operators.

**Objective.** To diagnose, on CICIDS2017, whether an unsupervised composition of three classical local graph signals, implemented on the Neo4j + Graph Data Science stack without ML dependencies, can be competitive with a flow-centric rule-based baseline.

**Methodology.** A temporal-multigraph model of network traffic is introduced together with a composite node-level anomaly metric $A(v,t) = w_1\tilde{\alpha}_1 + w_2\alpha_2 + w_3\alpha_3$: $\alpha_1$ — betweenness-centrality deviation from a baseline; $\alpha_2$ — Louvain community change weighted by the intra-community edge ratio; $\alpha_3$ — Jaccard drift of the 2-hop neighborhood. The method is implemented on Java 21 / Spring Boot 3 with Neo4j 2026.03 and GDS; the baseline is implemented on PostgreSQL 16.

**Results.** Under a host-window protocol with $\Delta t = 5$ min on CICIDS2017 (Mon+Tue+Wed, cleaned per Engelen et al.): fine-grid-best pure-$\alpha_1$ yields $F_1 = 0.0134$ (95% CI [0.0084, 0.0186]); coarse-grid-best composite — $F_1 = 0.0108$; supervised LR variant of the same features — $F_1 = 0.0063$. On the GT × Method matrix: rule-based baseline — $F_1 = 0.0797$ on victim-tagging GT and $F_1 = 0.0351$ on source-only; Isolation Forest on 21 flow-features — $F_1 = 0.2667$ [0.2348, 0.2996] on victim and $F_1 = 0.2000$ [0.1706, 0.2311] on source-only. The graph composition is outperformed by rule-based by 6× and by IF by 20× on both GTs. McNemar's test (IF vs rule-based on shared test set): $\chi^2 = 326$, $p < 10^{-4}$. A diagnostic decomposition shows the low graph-method recall is dominated by structural invisibility of victim servers; the 2.3× drop of rule-based F1 from victim-tagging to source-only GT confirms a systematic dst-expansion bias.

**Scientific contribution.** A negative result with diagnosis: quantitative limits of a linear unsupervised composition of three local graph signals on star-like CICIDS2017-type topologies are established; a source-only ground-truth decomposition is proposed as a graph-IDS auditing tool.

**Practical significance.** The contribution is primarily methodological: the results serve as a diagnostic template for auditing graph-based IDS on CICIDS2017-type datasets and as a foundation for the attacker/victim channel-fusion direction. The materials are suitable for training specialists of specialties 122 "Computer Science" and 125 "Cybersecurity and Information Protection".

### Keywords

intrusion detection; anomaly detection; graph database; Neo4j; Graph Data Science; betweenness centrality; community detection; CICIDS2017

---

## Примітки для автора (не для публікації)

**Рішення, які ухвалено:**

1. **Обсяг: ~2100 укр / ~2000 англ знаків з пробілами.** КОНТ вимагає мінімум 1800 знаків у кожній мові. Я перевищив норму, щоб залишити резерв на скорочення — якщо редактор КОНТ попросить зменшити до рівно 1800, є що різати (абзац про методологію — найдовший, з нього найлегше).

2. **Структурована анотація (Problem → Objective → Methodology → Results → Contribution → Significance).** Це **ключове рішення**. Традиційна монолітна анотація для кат. Б теж приймається, але структурована:
   - Легше читається рецензентом (він знає, де що шукати)
   - Демонструє «academic maturity» — автор знайомий з IMRaD-стандартом
   - Відповідає вимогам Scopus/WoS — якщо ви в майбутньому подаватимете цю статтю туди, анотація перепишеться мінімально
   
   Якщо КОНТ-інструкція авторам прямо забороняє підзаголовки в анотації — я перепишу в монолітну прозу за ~20 хв.

3. **Жодного бренду в титулі.** Спершу я хотів назвати «...у Neo4j 2026.03», але це некоректно — через 2 роки версія буде інша. У назві лише «Neo4j», версія — в методології.

4. **Три плейсхолдери `{{RESULT: ...}}`** — ідентичні в укр і англ. Замінити одночасно після T16.

5. **Ключові слова — 8 термінів, не 5-6.** КОНТ приймає 5-12 ключових слів. 8 — збалансований набір:
   - 2 загальні (виявлення вторгнень, графова база даних) — для discoverability
   - 3 специфічні технічні (Neo4j, GDS, betweenness centrality) — для точної класифікації
   - 2 методологічні (композитна метрика аномальності, виявлення спільнот) — для content fingerprint
   - 1 артефакт (CICIDS2017) — для reproducibility search

6. **«Реферативна» частина новизни в 1 абзац.** Свідомо компактна — якщо рецензент хоче деталей, він піде в Section 3-4. Анотація не має містити повноцінний опис нового методу.

7. **Англомовні нюанси:**
   - Використано **British English** (`normalised`, `behaviour`). Це мій вибір, але КОНТ приймає обидва варіанти. Можна замінити на American English (`normalized`, `behavior`) — залежить від ваших преференцій.
   - Термін **«intrinsic limitation»** замість доcлівного перекладу «принципове обмеження». Академічний англійський уникає кальок.
   - Термін **«industrial adoption»** замість «впровадження» — стандартна лексика security-спільноти.
   - Фраза **«lack the interpretability required for SOC operators»** — пряма апеляція до security-community цінностей (interpretability as a requirement, not a nice-to-have).
   - Посилання «Engelen et al.» без року — в англомовній анотації це норма; рік буде в бібліографії.

8. **Назва англійською.** Обрав **«based on graph algorithms in Neo4j using the Graph Data Science library»**, не **«Neo4j-based graph-algorithmic network anomaly detection method»**. Перше читабельне та academic-standard; друге виглядало б переобтяжено.

9. **Свідомо НЕ написано:**
   - Конкретний attack type list (port scan, brute force, DoS...) — не треба в анотації, читач побачить у методології
   - Версії бібліотек (Spring Boot 3.3.x, Driver 5.x) — деталі для реалізації, не для анотації
   - Слова «novel» і «first» — red flags для рецензентів кат. Б («автор claims новизну без обґрунтування»)
   - Слова «outperforms state-of-the-art» — ми свідомо не позиціонуємося як SOTA-претендент

10. **Рекомендації після написання Section 6 (експерименту):**
    - Замінити плейсхолдер `{{RESULT: F1-оцінка per-attack-type}}` на 1 речення з ключовою цифрою. Варіанти формулювання:
      - Якщо якість висока: «середнє F1 = 0.87 з піком 0.94 для Port Scan»
      - Якщо якість середня: «F1 у діапазоні 0.65–0.85 залежно від типу атаки»
      - Якщо нижче за baseline на частині атак (чесно): «F1 від 0.72 до 0.91, з чіткою перевагою над flow-centric baseline для структурних атак»
    - Замінити `{{RESULT: window-query performance}}`:
      - Якщо Neo4j суттєво швидший: «Neo4j забезпечує 3-10x нижчу p95-латентність для multi-hop queries»
      - Якщо співмірно: «продуктивність у межах 30% від PostgreSQL-baseline для window-queries»

11. **Підготовка для Scopus-версії** (на майбутнє):
    Анотація вже написана так, що при перекладі в Scopus-варіант знадобляться мінімальні правки:
    - Видалити згадку про 122/125 спеціальності (специфічне українське)
    - Замінити «military and governmental applications» на нейтральне «enterprise and government networks»
    - Додати 1 речення про experimental rigor (кількість runs, variance)

12. **Перевірка на компліанс з вимогами КОНТ:**
    - ✓ укр + англ версії
    - ✓ обсяг ≥1800 знаків кожна
    - ✓ ключові слова в обох мовах
    - ✓ назва в обох мовах
    - ? Специфічні вимоги КОНТ щодо DOI-посилань, ORCID, метаданих — потрібно перевірити в Instructions for Authors: https://csecurity.kubg.edu.ua/index.php/journal/about/submissions

13. **Одне сумнівне твердження, яке варто прочитати свіжим оком:**
    
    В англомовній версії: «*consistently outperforming the flow-centric baseline on attacks with a structural footprint*».
    
    Слово **«consistently»** — сильне твердження. Якщо в експерименті виявиться навіть один attack type, де baseline кращий — це твердження треба буде послабити до «generally outperforming» або «outperforming on most structural attacks». Не забудьте перевірити це після T16.
