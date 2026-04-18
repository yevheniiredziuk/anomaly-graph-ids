package ua.mitit.ids.detector.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ua.mitit.ids.common.neo4j.MigrationRunner;
import ua.mitit.ids.detector.baseline.GdsProjectionManager;

/**
 * End-to-end integration test for the sliding-window detector.
 *
 * <p>Tiny synthetic graph: A→B→C chain in two windows, then host X scans Y/Z/W in the third window.
 * The expected signal is an α₃ jump for X (brand-new 2-hop neighborhood).
 */
@Testcontainers(disabledWithoutDocker = true)
class SlidingWindowDetectorIT {

  private static Neo4jContainer<?> container;
  private static Driver driver;

  @BeforeAll
  static void setup() throws Exception {
    container =
        new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26-community"))
            .withAdminPassword("test-password")
            .withPlugins("graph-data-science", "apoc")
            .withEnv("NEO4J_dbms_security_procedures_unrestricted", "gds.*,apoc.*");
    container.start();

    driver =
        GraphDatabase.driver(container.getBoltUrl(), AuthTokens.basic("neo4j", "test-password"));
    new MigrationRunner(driver, "classpath:neo4j/migrations").migrate();

    try (Session session = driver.session()) {
      session.executeWriteWithoutResult(
          tx ->
              tx.run(
                  """
                  CREATE (a:Host {ip: 'A', baseline_bc_mean: 0.0, baseline_bc_std: 0.1})
                  CREATE (b:Host {ip: 'B', baseline_bc_mean: 1.0, baseline_bc_std: 0.1})
                  CREATE (c:Host {ip: 'C', baseline_bc_mean: 0.0, baseline_bc_std: 0.1})
                  CREATE (x:Host {ip: 'X', baseline_bc_mean: 0.0, baseline_bc_std: 0.5})
                  CREATE (y:Host {ip: 'Y', baseline_bc_mean: 0.0, baseline_bc_std: 0.5})
                  CREATE (z:Host {ip: 'Z', baseline_bc_mean: 0.0, baseline_bc_std: 0.5})
                  CREATE (w:Host {ip: 'W', baseline_bc_mean: 0.0, baseline_bc_std: 0.5})
                  CREATE (a)-[:CONNECTS_TO {start_time: datetime('2017-07-04T10:00:00Z'), end_time: datetime('2017-07-04T10:00:30Z'), label:'BENIGN'}]->(b)
                  CREATE (b)-[:CONNECTS_TO {start_time: datetime('2017-07-04T10:00:30Z'), end_time: datetime('2017-07-04T10:01:00Z'), label:'BENIGN'}]->(c)
                  CREATE (a)-[:CONNECTS_TO {start_time: datetime('2017-07-04T10:05:00Z'), end_time: datetime('2017-07-04T10:05:30Z'), label:'BENIGN'}]->(b)
                  CREATE (b)-[:CONNECTS_TO {start_time: datetime('2017-07-04T10:05:30Z'), end_time: datetime('2017-07-04T10:06:00Z'), label:'BENIGN'}]->(c)
                  CREATE (x)-[:CONNECTS_TO {start_time: datetime('2017-07-04T10:10:00Z'), end_time: datetime('2017-07-04T10:10:10Z'), label:'ATTACK'}]->(y)
                  CREATE (x)-[:CONNECTS_TO {start_time: datetime('2017-07-04T10:10:10Z'), end_time: datetime('2017-07-04T10:10:20Z'), label:'ATTACK'}]->(z)
                  CREATE (x)-[:CONNECTS_TO {start_time: datetime('2017-07-04T10:10:20Z'), end_time: datetime('2017-07-04T10:10:30Z'), label:'ATTACK'}]->(w)
                  """));
    }
  }

  @AfterAll
  static void teardown() {
    if (driver != null) {
      driver.close();
    }
    if (container != null) {
      container.stop();
    }
  }

  @Test
  void detects_anomaly_in_window_with_new_neighborhood() {
    WeightsConfig weights = new WeightsConfig();
    weights.setW1(0.3);
    weights.setW2(0.3);
    weights.setW3(0.4);
    weights.setThetaA(0.3);
    weights.setTheta1(2.0);
    weights.setEpsilon(1e-6);

    GdsProjectionManager pm = new GdsProjectionManager(driver);
    Alpha1Computer a1 = new Alpha1Computer(driver, weights);
    Alpha2Computer a2 = new Alpha2Computer(driver);
    Alpha3Computer a3 = new Alpha3Computer(driver);
    CompositeScorer scorer = new CompositeScorer(driver, weights);
    SlidingWindowDetector det = new SlidingWindowDetector(pm, a1, a2, a3, scorer, weights);

    setField(det, "windowSizeMinutes", 5);
    setField(det, "stepSizeMinutes", 5);
    setField(det, "samplingSize", 1000);
    setField(det, "samplingSeed", 42L);
    setField(det, "louvainSeed", 42L);

    SlidingWindowDetector.DetectionRun run =
        det.run(
            OffsetDateTime.parse("2017-07-04T10:00:00Z"),
            OffsetDateTime.parse("2017-07-04T10:15:00Z"));

    assertThat(run.windowsProcessed()).isEqualTo(3);

    try (Session session = driver.session()) {
      long eventsInWindow3 =
          session
              .run(
                  """
                  MATCH (e:AnomalyEvent)
                  WHERE e.t_window_start = datetime('2017-07-04T10:10:00Z')
                  RETURN count(e) AS n
                  """)
              .single()
              .get("n")
              .asLong();
      assertThat(eventsInWindow3).isGreaterThan(0);
    }
  }

  private static void setField(Object obj, String name, Object value) {
    try {
      var f = obj.getClass().getDeclaredField(name);
      f.setAccessible(true);
      f.set(obj, value);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
