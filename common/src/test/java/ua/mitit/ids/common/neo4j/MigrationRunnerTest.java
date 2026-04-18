package ua.mitit.ids.common.neo4j;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for MigrationRunner using a real Neo4j container.
 *
 * <p>Test migrations live in {@code src/test/resources/neo4j/migrations} and mirror the
 * detector-module production migrations (V001, V002).
 *
 * <p>{@code disabledWithoutDocker = true}: on machines whose Testcontainers cannot auto-discover a
 * working Docker endpoint (e.g. Docker Desktop on macOS where {@code docker-cli.sock} is a CLI
 * dispatcher, not the Engine API), tests are skipped rather than failing the build. The runner's
 * real-world behaviour is still exercised end-to-end by applying V001/V002 against the live Compose
 * Neo4j — see {@code scripts/verify-infra.sh}.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(OrderAnnotation.class)
class MigrationRunnerTest {

  private static Neo4jContainer<?> container;
  private static Driver driver;

  @BeforeAll
  static void setup() {
    container =
        new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26-community")).withoutAuthentication();
    container.start();

    driver = GraphDatabase.driver(container.getBoltUrl(), AuthTokens.none());
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
  @Order(1)
  void migrates_fresh_database() throws Exception {
    MigrationRunner runner = new MigrationRunner(driver, "classpath:neo4j/migrations");
    runner.migrate();

    try (Session session = driver.session()) {
      var tracking = session.run("MATCH (s:SchemaMigration) RETURN s.last_applied_version AS v");
      assertThat(tracking.hasNext()).isTrue();
      long version = tracking.next().get("v").asLong();
      assertThat(version).isEqualTo(2L);

      var constraints = session.run("SHOW CONSTRAINTS").list();
      assertThat(constraints)
          .extracting(r -> r.get("name").asString())
          .contains("host_ip_unique", "service_composite_unique");

      var indexes = session.run("SHOW INDEXES").list();
      assertThat(indexes)
          .extracting(r -> r.get("name").asString())
          .contains(
              "connects_to_start_time",
              "connects_to_end_time",
              "connects_to_label",
              "host_first_seen",
              "host_current_community",
              "host_baseline_bc_mean",
              "host_scoring");
    }
  }

  @Test
  @Order(2)
  void second_run_is_idempotent() throws Exception {
    long beforeTotal;
    try (Session session = driver.session()) {
      beforeTotal =
          session
              .run("MATCH (s:SchemaMigration) RETURN s.total_applied AS n")
              .single()
              .get("n")
              .asLong();
    }

    MigrationRunner runner = new MigrationRunner(driver, "classpath:neo4j/migrations");
    runner.migrate();

    try (Session session = driver.session()) {
      long afterTotal =
          session
              .run("MATCH (s:SchemaMigration) RETURN s.total_applied AS n")
              .single()
              .get("n")
              .asLong();
      assertThat(afterTotal).isEqualTo(beforeTotal);
    }
  }
}
