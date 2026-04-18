package ua.mitit.ids.benchmark.neo4j;

import java.util.Map;
import java.util.UUID;
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
 * Section 6.4 — Neo4j "Betweenness (approximate)". Full cycle: project → compute → drop. Matches
 * the detector's per-window pattern.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 15, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 15, timeUnit = TimeUnit.SECONDS)
@Fork(
    value = 1,
    jvmArgs = {"-Xms512m", "-Xmx1g"})
public class Neo4jBetweennessBenchmark {

  private static final String PROJECT =
      """
      CALL gds.graph.project.cypher(
          $name,
          'MATCH (h:Host) RETURN id(h) AS id',
          'MATCH (h1:Host)-[r:CONNECTS_TO]->(h2:Host)
           WHERE r.start_time >= datetime($tStart) AND r.start_time < datetime($tEnd)
           RETURN id(h1) AS source, id(h2) AS target',
          { parameters: { tStart: $tStart, tEnd: $tEnd } }
      ) YIELD graphName, nodeCount, relationshipCount
      RETURN graphName, nodeCount, relationshipCount
      """;

  private static final String BETWEENNESS =
      """
      CALL gds.betweenness.stream($name, {samplingSize: 1000, samplingSeed: 42})
      YIELD nodeId, score RETURN count(*) AS n
      """;

  private static final String DROP =
      "CALL gds.graph.drop($name, false) YIELD graphName RETURN graphName";

  @Benchmark
  public long betweennessFullCycle(InfrastructureState infra, WindowSampler sampler) {
    var window = sampler.next();
    String name = "bench_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    long result = 0;
    try (Session session = infra.neo4jDriver.session()) {
      long nodeCount =
          session.executeWrite(
              tx -> {
                var r =
                    tx.run(
                            PROJECT,
                            Map.of(
                                "name", name,
                                "tStart", window.start().toString(),
                                "tEnd", window.end().toString()))
                        .single();
                return r.get("nodeCount").asLong();
              });
      if (nodeCount > 0) {
        try {
          result =
              session.executeWrite(
                  tx -> tx.run(BETWEENNESS, Map.of("name", name)).single().get("n").asLong());
        } catch (Exception e) {
          // GDS betweenness can throw IllegalArgumentException on degenerate small graphs
          // ("bound must be positive"); Neo4j wraps the cause in a non-serializable
          // driver value which breaks JMH fork IPC — swallow and report zero.
          result = -1;
        }
      }
    } finally {
      try (Session session = infra.neo4jDriver.session()) {
        session.executeWrite(tx -> tx.run(DROP, Map.of("name", name)).consume());
      } catch (Exception ignored) {
        // best-effort cleanup
      }
    }
    return result;
  }
}
