package ua.mitit.ids.benchmark.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;
import ua.mitit.ids.benchmark.fixtures.InfrastructureState;
import ua.mitit.ids.benchmark.fixtures.WindowSampler;

/**
 * Section 6.4 — PostgreSQL "правило port scan (aggregate)".
 *
 * <p>Rolls back after each invocation so the INSERT side-effect of {@code detect_port_scan} does
 * not accumulate in {@code baseline_detections} during a measurement run.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 20, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(
    value = 1,
    jvmArgs = {"-Xms512m", "-Xmx1g"})
public class PostgresPortScanBenchmark {

  private static final String QUERY = "SELECT * FROM detect_port_scan(?, ?, 50)";

  @Benchmark
  public int portScan(InfrastructureState infra, WindowSampler sampler) throws Exception {
    var window = sampler.next();
    try (Connection conn = infra.postgresDataSource.getConnection()) {
      conn.setAutoCommit(false);
      try (PreparedStatement stmt = conn.prepareStatement(QUERY)) {
        stmt.setTimestamp(1, Timestamp.from(window.start().toInstant()));
        stmt.setTimestamp(2, Timestamp.from(window.end().toInstant()));
        int count = 0;
        try (ResultSet rs = stmt.executeQuery()) {
          while (rs.next()) {
            count++;
          }
        }
        conn.rollback();
        return count;
      }
    }
  }
}
