package ua.mitit.ids.benchmark.neo4j;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.neo4j.driver.Session;
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
 * Section 6.4 — Neo4j "2-hop околиця хоста". Time-window filtered, cycling through a small IP pool
 * so query cache can't cheat.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 20, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(
    value = 1,
    jvmArgs = {"-Xms512m", "-Xmx1g"})
public class Neo4jTwoHopBenchmark {

  // Actual CICIDS2017 testbed IPs (server/victim hosts) + known attacker.
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
      MATCH (v:Host {ip: $ip})
      OPTIONAL MATCH (v)-[r1:CONNECTS_TO]-(u:Host)
      WHERE r1.start_time >= datetime($tStart) AND r1.start_time < datetime($tEnd)
      WITH v, collect(DISTINCT u) AS one_hop
      OPTIONAL MATCH (v)-[r2:CONNECTS_TO]-(:Host)-[r3:CONNECTS_TO]-(w:Host)
      WHERE r2.start_time >= datetime($tStart) AND r2.start_time < datetime($tEnd)
        AND r3.start_time >= datetime($tStart) AND r3.start_time < datetime($tEnd)
        AND w.ip <> v.ip
      RETURN count(DISTINCT w) AS n
      """;

  @Benchmark
  public long twoHop(InfrastructureState infra, WindowSampler sampler) {
    var window = sampler.next();
    String ip = SAMPLE_IPS[Math.floorMod(window.start().hashCode(), SAMPLE_IPS.length)];
    try (Session session = infra.neo4jDriver.session()) {
      return session.executeRead(
          tx ->
              tx.run(
                      QUERY,
                      Map.of(
                          "ip", ip,
                          "tStart", window.start().toString(),
                          "tEnd", window.end().toString()))
                  .single()
                  .get("n")
                  .asLong());
    }
  }
}
