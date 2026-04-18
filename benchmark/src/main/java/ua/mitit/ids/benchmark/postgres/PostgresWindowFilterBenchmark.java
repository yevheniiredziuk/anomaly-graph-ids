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

/** Section 6.4 — PostgreSQL "фільтрація вікна за часом". */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 20, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(
    value = 1,
    jvmArgs = {"-Xms512m", "-Xmx1g"})
public class PostgresWindowFilterBenchmark {

  private static final String QUERY =
      "SELECT count(*) FROM flows WHERE t_start >= ? AND t_start < ?";

  @Benchmark
  public long windowFilter(InfrastructureState infra, WindowSampler sampler) throws Exception {
    var window = sampler.next();
    try (Connection conn = infra.postgresDataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(QUERY)) {
      stmt.setTimestamp(1, Timestamp.from(window.start().toInstant()));
      stmt.setTimestamp(2, Timestamp.from(window.end().toInstant()));
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
