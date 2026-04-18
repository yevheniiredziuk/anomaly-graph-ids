package ua.mitit.ids.benchmark.fixtures;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Logging;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Shared JMH state: Neo4j Driver + PostgreSQL DataSource.
 *
 * <p>Manual wiring (no Spring) — JMH and Spring Boot don't play well together when both try to
 * manage lifecycles. Reads env vars with defaults matching the Docker Compose setup.
 *
 * <ul>
 *   <li>{@code NEO4J_URI} / {@code NEO4J_USER} / {@code NEO4J_PASSWORD}
 *   <li>{@code POSTGRES_URL} / {@code POSTGRES_USER} / {@code POSTGRES_PASSWORD}
 * </ul>
 */
@State(Scope.Benchmark)
public class InfrastructureState {

  public Driver neo4jDriver;
  public DataSource postgresDataSource;

  @Setup(Level.Trial)
  public void setup() {
    String neo4jUri = envOrDefault("NEO4J_URI", "bolt://localhost:7687");
    String neo4jUser = envOrDefault("NEO4J_USER", "neo4j");
    String neo4jPassword = envOrDefault("NEO4J_PASSWORD", "changeme-local-only");

    Config config =
        Config.builder().withMaxConnectionPoolSize(5).withLogging(Logging.none()).build();
    neo4jDriver =
        GraphDatabase.driver(neo4jUri, AuthTokens.basic(neo4jUser, neo4jPassword), config);
    neo4jDriver.verifyConnectivity();

    String pgUrl = envOrDefault("POSTGRES_URL", "jdbc:postgresql://localhost:5432/ids");
    String pgUser = envOrDefault("POSTGRES_USER", "ids");
    String pgPassword = envOrDefault("POSTGRES_PASSWORD", "changeme-local-only");

    HikariConfig hc = new HikariConfig();
    hc.setJdbcUrl(pgUrl);
    hc.setUsername(pgUser);
    hc.setPassword(pgPassword);
    hc.setMaximumPoolSize(5);
    hc.setPoolName("jmh-pg");
    postgresDataSource = new HikariDataSource(hc);
  }

  @TearDown(Level.Trial)
  public void teardown() {
    if (neo4jDriver != null) {
      neo4jDriver.close();
    }
    if (postgresDataSource instanceof HikariDataSource hds) {
      hds.close();
    }
  }

  private static String envOrDefault(String key, String defaultValue) {
    String v = System.getenv(key);
    return v != null && !v.isBlank() ? v : defaultValue;
  }
}
