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

/** Section 6.4 — Neo4j "фільтрація вікна за часом". */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 20, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(
    value = 1,
    jvmArgs = {"-Xms512m", "-Xmx1g"})
public class Neo4jWindowFilterBenchmark {

  private static final String QUERY =
      """
      MATCH (h1:Host)-[r:CONNECTS_TO]->(h2:Host)
      WHERE r.start_time >= datetime($tStart)
        AND r.start_time <  datetime($tEnd)
      RETURN count(r) AS n
      """;

  @Benchmark
  public long windowFilter(InfrastructureState infra, WindowSampler sampler) {
    var window = sampler.next();
    try (Session session = infra.neo4jDriver.session()) {
      return session.executeRead(
          tx ->
              tx.run(
                      QUERY,
                      Map.of(
                          "tStart", window.start().toString(),
                          "tEnd", window.end().toString()))
                  .single()
                  .get("n")
                  .asLong());
    }
  }
}
