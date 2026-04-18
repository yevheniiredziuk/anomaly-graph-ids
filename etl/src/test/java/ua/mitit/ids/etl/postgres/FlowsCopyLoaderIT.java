package ua.mitit.ids.etl.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for {@link FlowsCopyLoader} + {@link HostsPopulator} using a real PostgreSQL
 * container.
 *
 * <p>Skips gracefully when Testcontainers cannot auto-discover a working Docker endpoint (see
 * {@code common/...MigrationRunnerTest} for the same pattern — on this machine Docker Desktop's
 * {@code docker-cli.sock} is a CLI dispatcher, not the Engine API). End-to-end behavior is still
 * exercised manually against the live Compose Postgres — see {@code scripts/verify-infra.sh} and
 * the T06 acceptance run.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(OrderAnnotation.class)
class FlowsCopyLoaderIT {

  private static PostgreSQLContainer<?> container;
  private static HikariDataSource dataSource;

  @BeforeAll
  static void setupContainer() throws Exception {
    container =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6-alpine"))
            .withDatabaseName("ids_test")
            .withUsername("ids_test")
            .withPassword("test-pwd");
    container.start();

    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(container.getJdbcUrl());
    cfg.setUsername(container.getUsername());
    cfg.setPassword(container.getPassword());
    cfg.setMaximumPoolSize(5);
    cfg.setAutoCommit(false);
    dataSource = new HikariDataSource(cfg);

    applySchema(dataSource);
  }

  @AfterAll
  static void teardown() {
    if (dataSource != null) {
      dataSource.close();
    }
    if (container != null) {
      container.stop();
    }
  }

  @Test
  @Order(1)
  void loads_csv_into_empty_flows() throws Exception {
    Path csv = Files.createTempFile("flows-test", ".csv");
    Files.writeString(
        csv,
        """
        source_ip,source_port,destination_ip,destination_port,protocol,t_start,t_end,flow_duration_us,bytes_fwd,bytes_bwd,packets_fwd,packets_bwd,syn_count,rst_count,psh_count,ack_count,fin_count,urg_count,label
        192.168.1.10,54321,10.0.0.5,80,6,2017-07-04T09:00:00+00:00,2017-07-04T09:00:01+00:00,1000000,512,2048,5,10,1,0,3,12,1,0,BENIGN
        192.168.1.11,33333,10.0.0.5,443,6,2017-07-04T09:00:02+00:00,2017-07-04T09:00:03+00:00,1500000,256,1024,3,6,1,0,2,8,1,0,BENIGN
        """);

    FlowsCopyLoader loader = new FlowsCopyLoader(dataSource);
    FlowsCopyLoader.LoadResult result = loader.load(csv, true);

    assertThat(result.rowsCopied()).isEqualTo(2);

    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM flows")) {
      rs.next();
      assertThat(rs.getLong(1)).isEqualTo(2);
    }

    // Verify recreated indexes exist on the partition (inherited from parent).
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = 'flows_tue'")) {
      long indexCount = 0;
      while (rs.next()) {
        indexCount++;
      }
      assertThat(indexCount).isGreaterThanOrEqualTo(1);
    }

    Files.deleteIfExists(csv);
  }

  @Test
  @Order(2)
  void populate_hosts_after_load() throws Exception {
    HostsPopulator populator = new HostsPopulator(dataSource);
    populator.populate();

    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(DISTINCT ip) FROM hosts")) {
      rs.next();
      // 2 source IPs (192.168.1.10, .11) + 1 destination IP (10.0.0.5) = 3 unique.
      assertThat(rs.getLong(1)).isEqualTo(3);
    }
  }

  private static void applySchema(DataSource ds) throws SQLException, IOException {
    Path moduleDir = Path.of(System.getProperty("user.dir"));
    Path schemaPath =
        resolveFirstExisting(
            moduleDir.resolve("../baseline/sql/init/01-schema.sql"),
            moduleDir.resolve("baseline/sql/init/01-schema.sql"));
    Path indexPath =
        resolveFirstExisting(
            moduleDir.resolve("../baseline/sql/init/02-indexes.sql"),
            moduleDir.resolve("baseline/sql/init/02-indexes.sql"));

    String schema = Files.readString(schemaPath);
    String indexes = Files.readString(indexPath);

    try (Connection conn = ds.getConnection();
        Statement stmt = conn.createStatement()) {
      conn.setAutoCommit(true);
      executeScript(stmt, schema);
      executeScript(stmt, indexes);
    }
  }

  private static Path resolveFirstExisting(Path... candidates) {
    for (Path p : candidates) {
      if (Files.exists(p)) {
        return p.normalize();
      }
    }
    throw new IllegalStateException(
        "None of these schema paths exist: " + java.util.Arrays.toString(candidates));
  }

  private static void executeScript(Statement stmt, String script) throws SQLException {
    for (String part : script.split(";")) {
      String trimmed = part.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("--")) {
        continue;
      }
      // pg_stat_statements may not be loadable without shared_preload_libraries — skip.
      if (trimmed.toUpperCase().contains("PG_STAT_STATEMENTS")) {
        continue;
      }
      stmt.execute(trimmed);
    }
  }
}
