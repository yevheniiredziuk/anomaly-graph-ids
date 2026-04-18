package ua.mitit.ids.etl.postgres;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Loads flows from a CSV file into the PostgreSQL {@code flows} table via {@code COPY FROM STDIN}.
 *
 * <p>Why COPY, not batch INSERT? For CICIDS2017 (~1.5 M rows) COPY is 10–100× faster: one SQL
 * statement, one network stream, minimal per-row overhead.
 *
 * <p>Workflow:
 *
 * <ol>
 *   <li>Drop performance indexes (3–5× COPY speedup)
 *   <li>Stream CSV via CopyManager
 *   <li>Recreate indexes
 *   <li>Refresh planner statistics (ANALYZE)
 * </ol>
 *
 * <p>Mirrors the drop-load-recreate pattern of {@code baseline/sql/load/load-flows.sh} (T04), but
 * in Java for programmatic control and Testcontainers-friendly testing.
 */
@Service
public class FlowsCopyLoader {

  private static final Logger log = LoggerFactory.getLogger(FlowsCopyLoader.class);

  private static final String COPY_SQL =
      """
      COPY flows (
          source_ip, source_port, destination_ip, destination_port, protocol,
          t_start, t_end, flow_duration_us,
          bytes_fwd, bytes_bwd, packets_fwd, packets_bwd,
          syn_count, rst_count, psh_count, ack_count, fin_count, urg_count,
          label
      )
      FROM STDIN
      WITH (FORMAT csv, HEADER true)
      """;

  // Index list mirrors baseline/sql/init/02-indexes.sql. No idx_flows_time_bucket — the
  // time_bucket_60s generated column was dropped in T04 (EXTRACT(EPOCH FROM timestamptz)
  // is not IMMUTABLE, which blocks generated-column expressions).
  private static final String[] DROP_INDEX_STATEMENTS = {
    "DROP INDEX IF EXISTS idx_flows_src_time",
    "DROP INDEX IF EXISTS idx_flows_dst_time",
    "DROP INDEX IF EXISTS idx_flows_pair_time",
    "DROP INDEX IF EXISTS idx_flows_label",
  };

  private static final String[] CREATE_INDEX_STATEMENTS = {
    "CREATE INDEX idx_flows_src_time ON flows (source_ip, t_start)",
    "CREATE INDEX idx_flows_dst_time ON flows (destination_ip, t_start)",
    "CREATE INDEX idx_flows_pair_time ON flows (source_ip, destination_ip, t_start)",
    "CREATE INDEX idx_flows_label ON flows (label) WHERE label <> 'BENIGN'",
  };

  private final DataSource dataSource;

  public FlowsCopyLoader(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Loads {@code csvPath} into {@code flows}. Assumes schema V001 (T04) already applied.
   *
   * @param truncateFirst if true, {@code TRUNCATE flows CASCADE} before loading
   * @return row count reported by COPY plus timing metadata
   */
  public LoadResult load(Path csvPath, boolean truncateFirst) throws SQLException, IOException {
    if (!Files.isReadable(csvPath)) {
      throw new IOException("CSV not readable: " + csvPath);
    }
    long fileSize = Files.size(csvPath);
    log.info("Loading {} ({} MB) into flows table...", csvPath, fileSize / (1024 * 1024));

    Instant overallStart = Instant.now();

    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);

      // Step 1: truncate (if requested) + drop indexes
      try {
        if (truncateFirst) {
          runStatement(conn, "TRUNCATE flows CASCADE");
          log.info("Truncated flows table");
        }
        for (String sql : DROP_INDEX_STATEMENTS) {
          runStatement(conn, sql);
        }
        log.info("Dropped {} indexes", DROP_INDEX_STATEMENTS.length);
        conn.commit();
      } catch (SQLException e) {
        conn.rollback();
        throw e;
      }

      // Step 2: COPY
      long rowsCopied;
      Duration copyDuration;
      try (InputStream in = new BufferedInputStream(Files.newInputStream(csvPath), 1 << 20)) {
        PGConnection pgConn = conn.unwrap(PGConnection.class);
        CopyManager copyManager = pgConn.getCopyAPI();
        Instant copyStart = Instant.now();
        rowsCopied = copyManager.copyIn(COPY_SQL, in, 1 << 20);
        copyDuration = Duration.between(copyStart, Instant.now());
        conn.commit();
        log.info(
            "COPY completed: {} rows in {} ms ({} rows/sec)",
            rowsCopied,
            copyDuration.toMillis(),
            rowsCopied * 1000 / Math.max(1, copyDuration.toMillis()));
      } catch (SQLException | IOException e) {
        conn.rollback();
        throw e;
      }

      // Step 3: recreate indexes
      Instant indexStart = Instant.now();
      try {
        for (String sql : CREATE_INDEX_STATEMENTS) {
          runStatement(conn, sql);
        }
        conn.commit();
        Duration indexDuration = Duration.between(indexStart, Instant.now());
        log.info(
            "Recreated {} indexes in {} ms",
            CREATE_INDEX_STATEMENTS.length,
            indexDuration.toMillis());
      } catch (SQLException e) {
        conn.rollback();
        throw e;
      }

      // Step 4: ANALYZE (requires autocommit — cannot run inside an open transaction)
      try {
        conn.setAutoCommit(true);
        runStatement(conn, "ANALYZE flows");
        conn.setAutoCommit(false);
      } catch (SQLException e) {
        log.warn("ANALYZE failed (non-fatal): {}", e.getMessage());
      }

      Duration overallDuration = Duration.between(overallStart, Instant.now());
      return new LoadResult(rowsCopied, fileSize, overallDuration, copyDuration);
    }
  }

  private static void runStatement(Connection conn, String sql) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute(sql);
    }
  }

  public record LoadResult(
      long rowsCopied, long fileSizeBytes, Duration totalDuration, Duration copyDuration) {

    public double throughputRowsPerSec() {
      return rowsCopied * 1000.0 / Math.max(1, copyDuration.toMillis());
    }

    public double throughputMbPerSec() {
      return (fileSizeBytes / 1024.0 / 1024.0) * 1000.0 / Math.max(1, copyDuration.toMillis());
    }
  }
}
