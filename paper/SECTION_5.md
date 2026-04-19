# Section 5. Реалізація

> **Статус:** чорновик v1 — готовий до вичитки.
> **Обсяг:** ~1100 слів / ~8 500 знаків.
> **Нумерація:** таблиця 5.1, лістинги 5.1–5.5.

---

## 5. РЕАЛІЗАЦІЯ

Розділ описує практичну реалізацію методу, формалізованого в розділі 3 та деталізованого в розділі 4. Виклад побудовано у порядку, що відповідає потоку обробки: (5.1) — архітектура системи та вибір технологій; (5.2) — схема Neo4j та ключові Cypher-запити; (5.3) — інтеграційний шар Spring Data Neo4j; (5.4) — реалізація PostgreSQL-baseline для порівняльного експерименту.

### 5.1. Технологічний стек та архітектура

Система реалізована як multi-module Maven-проєкт на мові Java 21 з використанням Spring Boot 3 як платформи для обох основних сервісів (ETL та Detector). Підсумковий стек зведено у таблиці 5.1.

**Таблиця 5.1 — Склад технологічного стеку**

| Компонент | Версія | Призначення |
|---|---|---|
| Java | 21 LTS | Основна мова реалізації |
| Spring Boot | 3.3.x | Платформа для сервісів ETL та Detector |
| Spring Data Neo4j | 7.x | ORM-шар для Neo4j |
| Neo4j Java Driver | 5.x | Прямий доступ до Bolt-протоколу для batch-операцій |
| Neo4j | 2026.03 Community | Графова СКБД |
| Neo4j Graph Data Science | 2.x | Бібліотека графових алгоритмів |
| PostgreSQL | 16 | Реляційна СКБД для baseline-експерименту |
| JMH | 1.37 | Мікро-бенчмарки продуктивності |
| Docker Compose | — | Оркестрація СКБД у dev-середовищі |

Архітектурно система реалізована за принципом **persistence-first**: усі стани (сирий граф, baseline-статистики, результати детекції) зберігаються в Neo4j як персистентний стан; Java-сервіси є stateless-обробниками, що послідовно трансформують цей стан. Це забезпечує відновлюваність обчислень після перезапуску сервісу та спрощує debug-процедури — будь-який проміжний стан перевіряється через Neo4j Browser або cypher-shell.

Суттєвим свідомим обмеженням реалізації є **повна відсутність Python-залежностей** у production-коді (winkові Python-скрипти використовуються виключно для одноразового препроцесингу датасету, поза runtime-контуром системи). Це рішення обґрунтоване практичними вимогами enterprise-середовищ, де heterogeneous-стек (Python + Java) значно ускладнює розгортання та підтримку.

### 5.2. Схема Neo4j та ключові Cypher-запити

Схема Neo4j реалізує формальну модель (3.1) з прямим відображенням типів вузлів і ребер на Neo4j labels/relationship types (див. таблицю 3.1). Ініціалізація схеми та індексів виконується одноразово з міграційного скрипту:

**Лістинг 5.1 — Ініціалізація схеми**

```cypher
CREATE CONSTRAINT host_ip_unique IF NOT EXISTS
    FOR (h:Host) REQUIRE h.ip IS UNIQUE;

CREATE CONSTRAINT service_composite_unique IF NOT EXISTS
    FOR (s:Service) REQUIRE (s.host_ip, s.port, s.protocol) IS UNIQUE;

CREATE INDEX connects_to_start_time IF NOT EXISTS
    FOR ()-[r:CONNECTS_TO]-() ON (r.start_time);

CREATE INDEX host_current_community IF NOT EXISTS
    FOR (h:Host) ON (h.current_community);
```

Індекс на `start_time` є критичним для продуктивності: фільтрація вікон (рядок 4 Алгоритму 4.2) виконується на кожному кроці детекції, і без індексу її складність стає $\mathcal{O}(|E|)$ замість $\mathcal{O}(|W(t, \Delta t)|)$. Індекс на `current_community` обслуговує обчислення $\rho$ (формула 3.9).

**Лістинг 5.2 — Batch-завантаження агрегованих ребер з CSV**

Реальне ETL-завантаження використовує `UNWIND` для amortизації costs транзакції. Клієнт передає batch ребер як список параметрів, сервер за один прохід обробляє всю партію:

```cypher
// Parameters: $edges — список об'єктів (src_ip, dst_ip, protocol, start_time, ...)
UNWIND $edges AS e
MERGE (src:Host {ip: e.src_ip})
  ON CREATE SET src.first_seen = datetime(e.start_time)
  ON MATCH  SET src.last_seen  = datetime(e.end_time)
MERGE (dst:Host {ip: e.dst_ip})
  ON CREATE SET dst.first_seen = datetime(e.start_time)
  ON MATCH  SET dst.last_seen  = datetime(e.end_time)
CREATE (src)-[r:CONNECTS_TO {
    start_time:  datetime(e.start_time),
    end_time:    datetime(e.end_time),
    protocol:    e.protocol,
    bytes_fwd:   e.bytes_fwd,
    bytes_bwd:   e.bytes_bwd,
    packets_fwd: e.packets_fwd,
    packets_bwd: e.packets_bwd,
    label:       e.label
}]->(dst);
```

`MERGE` на хостах забезпечує ідемпотентність: повторне виконання з тим самим вхідним batch-ем не створює дублікатів. `CREATE` на ребрі є свідомим вибором: кожен bucket — окреме ребро в мультиграфі (модель 3.1), дублікатів не може бути за визначенням.

**Лістинг 5.3 — Проекція вікна для GDS-аналізу**

Для кожного аналітичного вікна створюється in-memory GDS-проекція — компактне представлення графа в форматі, оптимізованому для алгоритмів:

```cypher
// Parameters: $winName (string), $tStart, $tEnd (datetime)
CALL gds.graph.project.cypher(
    $winName,
    'MATCH (h:Host) RETURN id(h) AS id',
    'MATCH (h1:Host)-[r:CONNECTS_TO]->(h2:Host)
     WHERE r.start_time >= datetime($tStart)
       AND r.start_time <  datetime($tEnd)
     RETURN id(h1) AS source, id(h2) AS target, count(r) AS weight',
    { parameters: { tStart: $tStart, tEnd: $tEnd } }
) YIELD graphName, nodeCount, relationshipCount;
```

Після завершення обробки вікна проекція обов'язково звільняється через `gds.graph.drop($winName)` — без цього in-memory проекції накопичуються і призводять до OOM на послідовностях з сотень вікон.

**Лістинг 5.4 — Обчислення $\rho$ (intra-community edge ratio)**

Формула (3.9) з розділу 3 реалізується одним Cypher-запитом з інтегруванням результатів Louvain, записаних у властивість `current_community`:

```cypher
// Parameters: $ip — IP-адреса хоста, для якого обчислюємо ρ
MATCH (v:Host {ip: $ip})-[r:CONNECTS_TO]-(u:Host)
WHERE r.start_time >= datetime($tStart)
  AND r.start_time <  datetime($tEnd)
WITH v, u,
     u.current_community AS c_other,
     v.current_community AS c_self
WITH v,
     count(*) AS total_deg,
     sum(CASE WHEN c_other = c_self THEN 1 ELSE 0 END) AS intra_deg
RETURN CASE
         WHEN total_deg = 0 THEN 0.0
         ELSE toFloat(intra_deg) / total_deg
       END AS rho;
```

Запит виконується за $\mathcal{O}(d_v)$ завдяки індексам. Аналогічний запит для batch-обчислення ρ одразу для всіх вузлів вікна використовує `MATCH (v:Host) WHERE v.current_community IS NOT NULL ... WITH v, u COLLECT ...`, що дозволяє амортизувати фіксовані витрати.

### 5.3. Інтеграційний шар: Spring Data Neo4j

Сервіс Detector використовує Spring Data Neo4j для декларативного опису domain-моделі і парний з ним `Neo4jClient` для executor-style виконання складних параметризованих запитів (UNWIND з batch-ами, GDS-procedure-calls).

**Лістинг 5.5 — Виконання композитного scoring через Neo4jClient**

```java
@Service
@RequiredArgsConstructor
public class AnomalyScoringService {

    private final Neo4jClient neo4jClient;
    private final ScoringWeights weights;  // w_1, w_2, w_3, θ_A

    /**
     * Обчислення композитної метрики A(v, t) для всіх вузлів поточного вікна.
     * Припускається, що BC, Louvain та ρ вже обчислено та записано як властивості вузлів
     * у поточному вікні (див. WindowDetector).
     */
    public List<AnomalyScore> scoreWindow(Instant tStart, Instant tEnd) {
        return neo4jClient.query("""
            MATCH (v:Host)
            WHERE v.current_bc IS NOT NULL
              AND v.baseline_bc_std IS NOT NULL
            WITH v,
                 // α_1 — z-score відхилення BC, через сигмоїду
                 1.0 / (1.0 + exp(-(
                     abs(v.current_bc - v.baseline_bc_mean)
                     / (v.baseline_bc_std + $epsilon)
                     - $theta1
                 ))) AS alpha1_norm,
                 // α_2 — зміна спільноти, зважена на ρ
                 CASE
                     WHEN v.previous_community IS NULL THEN 0.0
                     WHEN v.current_community <> v.previous_community
                          THEN coalesce(v.previous_rho, 0.0)
                     ELSE 0.0
                 END AS alpha2,
                 // α_3 — Jaccard drift околиці (передобчислений)
                 coalesce(v.neighborhood_drift, 0.0) AS alpha3
            WITH v,
                 $w1 * alpha1_norm + $w2 * alpha2 + $w3 * alpha3 AS score
            SET v.current_anomaly_score = score,
                v.is_anomalous = score > $thetaA
            RETURN v.ip AS ip, score, score > $thetaA AS anomalous
            ORDER BY score DESC
            """)
            .bindAll(Map.of(
                "w1",      weights.w1(),
                "w2",      weights.w2(),
                "w3",      weights.w3(),
                "theta1",  weights.theta1(),
                "thetaA",  weights.thetaA(),
                "epsilon", 1e-6
            ))
            .fetchAs(AnomalyScore.class)
            .mappedBy((ts, rec) -> new AnomalyScore(
                rec.get("ip").asString(),
                rec.get("score").asDouble(),
                rec.get("anomalous").asBoolean()
            ))
            .all()
            .stream().toList();
    }
}
```

Рішення про виконання скорингу цілком на стороні Neo4j (через Cypher), а не в Java-коді, обґрунтоване двома міркуваннями. По-перше, це уникає двобічної передачі між СКБД та додатком: властивості вузлів читаються, обчислюються і записуються назад в одній транзакції. По-друге, результат (`v.current_anomaly_score`) одразу доступний для подальших запитів і візуалізації через Neo4j Browser без додаткових синхронізацій.

Конфігурація драйвера включає `connection pooling` з `maxConnectionPoolSize = 50` та `connectionAcquisitionTimeout = 60s` — стандартні налаштування для enterprise-середовища, що витримують параметри sliding-window-детектора з 5-секундним кроком.

### 5.4. Реалізація PostgreSQL-baseline

Baseline-реалізація виявляє типи атак з класичною flow-centric парадигми засобами rule-based детекції на PostgreSQL 16. Схема містить таблицю `flows` з індексами на `(src_ip, time_bucket)` та `(dst_ip, time_bucket)`, та набір детекторних функцій у PL/pgSQL.

**Лістинг 5.6 — Приклад rule-based детектора Port Scan**

```sql
-- Виявлення Port Scan: хост протягом хвилини ініціює з'єднання
-- на >= N різних портів унікальних призначень.
CREATE OR REPLACE FUNCTION detect_port_scan(
    p_t_start       TIMESTAMPTZ,
    p_t_end         TIMESTAMPTZ,
    p_port_threshold INTEGER DEFAULT 50
)
RETURNS TABLE (src_ip INET, unique_ports INTEGER)
LANGUAGE SQL STABLE AS $$
    SELECT
        f.src_ip,
        COUNT(DISTINCT f.dst_port) AS unique_ports
    FROM flows f
    WHERE f.t_start >= p_t_start
      AND f.t_start <  p_t_end
    GROUP BY f.src_ip
    HAVING COUNT(DISTINCT f.dst_port) >= p_port_threshold;
$$;
```

Детектори DoS, Brute Force реалізовано за аналогічним принципом: кожен — окрема функція з власним набором порогів, підібраним на тій самій training-вибірці що й порогу $\theta_A$ для графового метода. Це забезпечує чесність порівняння: обидва методи «чесно» налаштовані на training-частині датасету, а тестуються на hold-out-вибірці.

Свідоме обмеження baseline — відсутність ML-компонента (наприклад, Random Forest як класифікатора на flow-features). Таке розширення baseline мало б переваги, але його підготовка виходить за scope роботи; порівняння з ML-based flow-centric IDS є одним з напрямів подальших досліджень (секція 7).

Підсумовуючи, реалізація системи виконана на індустріальному Java-стеку без Python-залежностей у production-коді, з чітким відокремленням персистентного стану (Neo4j / PostgreSQL) від обробників (Spring Boot сервіси). Вся обчислювально важка логіка виконується у Neo4j через Cypher+GDS — це мінімізує data transfer між базою та сервісами і використовує сильні сторони графової СКБД для pattern matching. Експериментальна оцінка методу та порівняння з PostgreSQL-baseline розглянуті в наступному розділі.

---

## Примітки для автора (не для публікації)

**Рішення, які ухвалено:**

1. **Обсяг 8 500 знаків.** У нормі для статті — це найдовший-після огляду розділ, де приведено код. Рецензенти кат. Б приймають більший обсяг Section 5, якщо код виглядає production-level.

2. **5 код-лістингів, не більше.** Менше — не покаже архітектуру; більше — перевантажить. Свідомо вибрані:
   - Schema init (Cypher, лістинг 5.1) — рецензент впевнюється в коректності моделі
   - Batch load (5.2) — показує UNWIND як industrial pattern
   - GDS projection (5.3) — ядро методу
   - ρ computation (5.4) — реалізація нашої композитної метрики
   - Scoring service (5.5) — як Java-код інтегрований з Cypher
   - Port scan detector (5.6) — приклад baseline для чесного порівняння

3. **Свідомо НЕ наведено:**
   - `@Configuration` класи Spring Boot — boilerplate
   - `main()` методи — тривіально
   - Повний `domain` model (POJO) — тільки згадано
   - Testcontainers setup — хоча важливий для reproducibility, він виходить за скоп
   - Exception handling та retries — для production-якості потрібні, але в статті загромоздять

4. **Код `exp()` у сигмоїді.** Cypher не має прямого `exp`. Використовується `exp(x)` з Neo4j `Mathematical` функцій (доступна з APOC як `apoc.math.exp(x)` або нативно в Cypher 5+ як `exp(x)`). Перевірте це під час реалізації в T13; якщо виявиться що нативного `exp` немає в тій версії — використайте APOC або winесіть нормалізацію в Java-код.

5. **Рецензент може задати три важкі питання:**
   - «Чому ви не використали Neo4j `DateTime` індексну процедуру `db.index.fulltext.queryNodes` для темпоральної фільтрації?» — Відповідь: range index на `start_time` ефективніший для нашого паттерну (continuous time range), fulltext index оптимізований для discrete queries.
   - «Навіщо і PostgreSQL і Neo4j, чому не все в Neo4j?» — Відповідь: для чесного benchmark-порівняння, не для production-архітектури. У production-варіанті ми б не мали дубльованого зберігання.
   - «Скільки часу займає одна ітерація sliding window?» — Це в Section 6 (експериментальні результати). Тут не відповідаємо, лише оцінимо асимптотично у (4.2).

6. **Версії компонентів у таблиці 5.1.** Я написав «2026.03» для Neo4j — це те, що буде фактично в Docker Compose (див. T02). Коли T02 виконаємо, треба буде перевірити точну версію (наприклад, `2026.03.1`) і уточнити в таблиці 5.1 перед подачею.

7. **Архітектурний акцент «persistence-first».** Свідомий choice. В сучасних статтях з graph analytics є тенденція хвалитися in-memory-only архітектурою — ми йдемо від протилежного: пишемо, що для IDS ми обираємо persistence. Це семантично адекватно для security-критичних систем (якщо детектор впав, ми маємо historical state).

8. **Підказки для fine-tuning перед подачею:**
   - Перевірте чи Spring Data Neo4j 7.x вимагає/дозволяє `@EnableReactiveNeo4jRepositories` замість `@EnableNeo4jRepositories` для нашого use case. Неблокуючий driver може бути корисний для sliding window (паралельні вікна).
   - Перевірте чи ваш JDBC template для PostgreSQL використовує `batchUpdate` (amortize per-row overhead). Для PostgreSQL це еквівалент Neo4j UNWIND.

9. **Можливі правки Section 5 після реалізації:**
   - Якщо в T13 виявиться, що `exp()` треба робити через APOC — оновити лістинг 5.5.
   - Якщо в T17 (benchmarks) виявиться, що batch size 10 000 неоптимальний — оновити лістинг 5.2 з рекомендованим значенням.
   - Якщо через GDS API зміниться signature — оновити лістинг 5.3.

10. **НЕ написано про scaling.** Production-level масштабування Neo4j (clustering, read replicas, write failover) — свідомо виключено зі scope. Стаття про метод, а не про operationalization. Це для окремої роботи у перспективі.
