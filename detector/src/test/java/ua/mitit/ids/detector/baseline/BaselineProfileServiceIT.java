package ua.mitit.ids.detector.baseline;

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

/**
 * Integration test for {@link BaselineProfileService} using real Neo4j+GDS container.
 *
 * <p>Skips gracefully when Testcontainers cannot discover Docker (same pattern as earlier ITs).
 */
@Testcontainers(disabledWithoutDocker = true)
class BaselineProfileServiceIT {

  private static Neo4jContainer<?> container;
  private static Driver driver;
  private static BaselineProfileService service;

  @BeforeAll
  static void setup() throws Exception {
    container =
        new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26-community"))
            .withAdminPassword("test-password")
            .withPlugins("graph-data-science")
            .withEnv("NEO4J_dbms_security_procedures_unrestricted", "gds.*,apoc.*");
    container.start();

    driver =
        GraphDatabase.driver(container.getBoltUrl(), AuthTokens.basic("neo4j", "test-password"));

    new MigrationRunner(driver, "classpath:neo4j/migrations").migrate();

    GdsProjectionManager pm = new GdsProjectionManager(driver);
    service = new BaselineProfileService(driver, pm);
    setField(service, "snapshotDurationMinutes", 5);
    setField(service, "samplingSize", 1000);
    setField(service, "samplingSeed", 42L);
    setField(service, "minSamplesPerHost", 2);

    try (Session session = driver.session()) {
      session.executeWriteWithoutResult(
          tx ->
              tx.run(
                  """
                  CREATE (a:Host {ip: '10.0.0.1'})
                  CREATE (b:Host {ip: '10.0.0.2'})
                  CREATE (c:Host {ip: '10.0.0.3'})
                  CREATE (a)-[:CONNECTS_TO {start_time: datetime('2017-07-03T10:00:00Z'), end_time: datetime('2017-07-03T10:00:30Z')}]->(b)
                  CREATE (b)-[:CONNECTS_TO {start_time: datetime('2017-07-03T10:00:15Z'), end_time: datetime('2017-07-03T10:00:45Z')}]->(c)
                  CREATE (a)-[:CONNECTS_TO {start_time: datetime('2017-07-03T10:05:00Z'), end_time: datetime('2017-07-03T10:05:30Z')}]->(b)
                  CREATE (b)-[:CONNECTS_TO {start_time: datetime('2017-07-03T10:05:15Z'), end_time: datetime('2017-07-03T10:05:45Z')}]->(c)
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
  void computes_baseline_for_synthetic_graph() {
    BaselineProfileService.BaselineResult result =
        service.compute(
            OffsetDateTime.parse("2017-07-03T10:00:00Z"),
            OffsetDateTime.parse("2017-07-03T10:10:00Z"));

    assertThat(result.snapshotsProcessed()).isEqualTo(2);
    assertThat(result.hostsWithSufficientSamples()).isGreaterThanOrEqualTo(3);

    try (Session session = driver.session()) {
      long bSamples =
          session
              .run("MATCH (h:Host {ip:'10.0.0.2'}) RETURN h.baseline_samples AS n")
              .single()
              .get("n")
              .asLong();
      assertThat(bSamples).isEqualTo(2);
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
