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

/** Section 6.4 — PostgreSQL "2-hop околиця хоста" via explicit self-joins. */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 20, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(
    value = 1,
    jvmArgs = {"-Xms512m", "-Xmx1g"})
public class PostgresTwoHopBenchmark {

  private static final String[] SAMPLE_IPS = {
    "172.16.0.1",
    "192.168.10.3",
    "192.168.10.50",
    "192.168.10.51",
    "192.168.10.14",
    "192.168.10.15",
    "192.168.10.25",
  };

  private static final String QUERY =
      """
      WITH hop1 AS (
          SELECT DISTINCT destination_ip AS ip
          FROM flows
          WHERE source_ip = ?::INET
            AND t_start >= ? AND t_start < ?
      )
      SELECT count(DISTINCT f2.destination_ip) AS n
      FROM hop1 h
      JOIN flows f2 ON f2.source_ip = h.ip
      WHERE f2.t_start >= ? AND f2.t_start < ?
      """;

  @Benchmark
  public long twoHop(InfrastructureState infra, WindowSampler sampler) throws Exception {
    var window = sampler.next();
    String ip = SAMPLE_IPS[Math.floorMod(window.start().hashCode(), SAMPLE_IPS.length)];
    Timestamp tStart = Timestamp.from(window.start().toInstant());
    Timestamp tEnd = Timestamp.from(window.end().toInstant());

    try (Connection conn = infra.postgresDataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(QUERY)) {
      stmt.setString(1, ip);
      stmt.setTimestamp(2, tStart);
      stmt.setTimestamp(3, tEnd);
      stmt.setTimestamp(4, tStart);
      stmt.setTimestamp(5, tEnd);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
