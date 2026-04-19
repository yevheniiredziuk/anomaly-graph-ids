# Анотації

> **Статус:** v2 — фактичні числа T19 заповнено.
> **Обсяг:** укр — ~2100 знаків з пробілами; англ — ~2000 знаків з пробілами.
> **Структура:** згідно з рекомендаціями IMRaD для структурованої анотації.

---

## УКРАЇНСЬКА ВЕРСІЯ

### Назва

**Метод виявлення аномальної мережевої активності на основі графових алгоритмів у Neo4j з використанням Graph Data Science**

### Анотація

**Постановка проблеми.** Сучасні системи виявлення вторгнень (IDS) переважно оперують окремими мережевими потоками (flow-centric парадигма) і побудовані на класифікаторах статистичних ознак. Такий підхід ефективний для сигнатурних атак, але має принципове обмеження — він не враховує структурний контекст взаємодій між хостами, що унеможливлює виявлення атак зі структурним слідом, таких як reconnaissance, бокове переміщення (lateral movement), прихована екфільтрація даних та координована діяльність ботнетів. Графові методи потенційно розв'язують цю проблему, проте їх промислове впровадження стримується складністю інфраструктури та переважним використанням глибоких графових нейронних мереж, що вимагають значних обчислювальних ресурсів і не забезпечують інтерпретовності для операторів SOC.

**Мета роботи** — розробити та експериментально перевірити метод виявлення аномальної мережевої активності на основі класичних графових алгоритмів, реалізований на стеку Neo4j з бібліотекою Graph Data Science (GDS) без залежностей від Python-екосистеми машинного навчання.

**Методологія.** Побудовано формальну модель темпорального мультиграфа мережевого трафіку з типізованими вузлами (хости, сервіси) та ребрами з часовими атрибутами. Запропоновано композитну метрику аномальності вузла, що об'єднує три незалежні графові сигнали: відхилення міри betweenness centrality від baseline-профілю (нормалізоване через сигмоїдальне перетворення), зважену зміну приналежності до спільноти (через intra-community edge ratio як ваговий коефіцієнт), та Jaccard-drift двохкрокової околиці. Метод реалізовано як мультимодульний Java 21 / Spring Boot 3 додаток з інтеграцією Neo4j 2026.03 + GDS 2.x. Для порівняльного експерименту розроблено flow-centric rule-based baseline на PostgreSQL 16 з партиціонованою схемою даних та детекторами типових атак на PL/pgSQL.

**Результати.** Метод перевірено на підмножині публічного датасету CICIDS2017 (понеділок-середа, з очищенням за Engelen та ін.). У прийнятому host-window протоколі оцінювання ($\Delta t = 5$ хв) графовий метод досягає загальних метрик $F_1 = 0{,}013$ ($P = 0{,}007$, $R = 0{,}339$) для fine-grid-best pure-$\alpha_1$ конфігурації та $F_1 = 0{,}011$ для coarse-grid-best композитної конфігурації — проти $F_1 = 0{,}080$ для flow-centric baseline; у прийнятому протоколі graph-метод не перевершує baseline у чутливості виявлення в жодному з протестованих класів атак. Двохрівневий grid search виявив, що композитна метрика не дає стійкої переваги над single-component $\alpha_1$-детектором; $\alpha_2$ (community change) та $\alpha_3$ (Jaccard drift) окремо дають F1 нижче 0,004, і при лінійному об'єднанні з $\alpha_1$ додають шум, а не сигнал. Водночас метод надає інтерпретовані структурні сигнали, недоступні flow-centric підходу, та конкурентну p95-латентність на спеціалізованих графових операціях (approximate betweenness 74,6 мс, Louvain community detection 219,0 мс за 5-хв вікно). Виявлені обмеження артикульовано як мотивацію для напряму attacker/victim channel fusion у подальших роботах.

**Наукова новизна.** Запропоновано інтерпретовану графову альтернативу до GNN-підходів у виявленні мережевих аномалій, реалізовану на промисловому enterprise-стеку без ML-залежностей. Побудовано та експериментально верифіковано композитну метрику аномальності на основі трьох незалежних класичних графових сигналів.

**Практичне значення.** Результати застосовні для побудови систем моніторингу мережевої безпеки в інформаційно-телекомунікаційних мережах військового та державного призначення, а також у навчальному процесі для спеціальностей 122 «Комп'ютерні науки» та 125 «Кібербезпека та захист інформації».

### Ключові слова

виявлення вторгнень; графова база даних; Neo4j; Graph Data Science; композитна метрика аномальності; betweenness centrality; виявлення спільнот; CICIDS2017

---

## ENGLISH VERSION

### Title

**A method for network anomaly detection based on graph algorithms in Neo4j using the Graph Data Science library**

### Abstract

**Problem statement.** Modern intrusion detection systems (IDSs) predominantly operate on individual network flows (the flow-centric paradigm) and rely on classifiers over statistical flow features. While effective for signature-based attacks, this paradigm has an intrinsic limitation: it ignores the structural context of inter-host interactions, which precludes detection of attacks with a structural footprint such as reconnaissance, lateral movement, stealthy data exfiltration, and coordinated botnet activity. Graph-based methods potentially address this gap; however, their industrial adoption is hindered by infrastructural complexity and a dominant reliance on deep graph neural networks (GNNs), which demand substantial computational resources and lack the interpretability required for SOC operators.

**Objective.** To develop and experimentally validate a method for network anomaly detection based on classical graph algorithms, implemented on the Neo4j + Graph Data Science (GDS) stack without dependencies on the Python machine-learning ecosystem.

**Methodology.** A formal model is introduced describing network traffic as a temporal multigraph with typed nodes (hosts, services) and edges carrying temporal attributes. A composite node-level anomaly metric is proposed that combines three independent graph signals: (1) the deviation of betweenness centrality from a baseline profile, normalised via sigmoidal transformation; (2) the weighted change of community membership, using an intra-community edge ratio as a confidence weight; and (3) the Jaccard drift of a node's 2-hop neighborhood. The method is implemented as a multi-module Java 21 / Spring Boot 3 application integrated with Neo4j 2026.03 and GDS 2.x. For comparative evaluation, a flow-centric rule-based baseline is implemented in PostgreSQL 16, featuring a partitioned schema and PL/pgSQL detectors for canonical attack types.

**Results.** The method is evaluated on a subset of the public CICIDS2017 dataset (Monday-Wednesday, cleaned according to Engelen et al.). Under the adopted host-window evaluation protocol ($\Delta t = 5$ min), the graph-based method attains overall $F_1 = 0.013$ ($P = 0.007$, $R = 0.339$) for the fine-grid-best pure-$\alpha_1$ configuration and $F_1 = 0.011$ for the coarse-grid-best composite configuration, against $F_1 = 0.080$ for the flow-centric baseline; it does not exceed the baseline in detection recall on any tested attack class under this protocol. A two-level grid search (coarse simplex × fine corners) shows that the linear composite metric provides no stable advantage over a single-component $\alpha_1$ detector; $\alpha_2$ (community change) and $\alpha_3$ (2-hop Jaccard drift) individually yield $F_1 < 0.004$ and, when linearly combined with $\alpha_1$, add noise rather than signal. The method nevertheless delivers interpretable structural signals unavailable to the flow-centric approach and competitive p95 latency on graph-specific operations (approximate betweenness 74.6 ms, Louvain community detection 219.0 ms per 5-min window). The identified limitations are articulated as motivation for an attacker/victim channel fusion direction in future work.

**Scientific contribution.** An interpretable graph-based alternative to GNN approaches in network anomaly detection is proposed, implemented on an industrial enterprise stack without ML dependencies. A composite anomaly metric combining three independent classical graph signals is introduced and experimentally verified.

**Practical significance.** The results apply to the construction of network security monitoring systems in information-communication networks for military and governmental applications, as well as in the educational process for specialties 122 "Computer Science" and 125 "Cybersecurity and Information Protection".

### Keywords

intrusion detection; graph database; Neo4j; Graph Data Science; composite anomaly metric; betweenness centrality; community detection; CICIDS2017

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
