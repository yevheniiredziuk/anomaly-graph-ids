package ua.mitit.ids.evaluation.collectors;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.mitit.ids.evaluation.groundtruth.GroundTruthBuilder.HostWindow;

/**
 * Reads raw :AnomalyEvent from Neo4j with their stored α components, so grid search can re-score
 * each event with different (w1, w2, w3, θA) combinations without re-running the detector.
 *
 * <p>For this to be complete, the detector must have been run with θA=0.0 so that events were
 * written regardless of score. Otherwise only events above the original θA are available.
 */
@Component
public class GraphPredictionsCollector {

  private static final Logger log = LoggerFactory.getLogger(GraphPredictionsCollector.class);

  private static final String FETCH_EVENTS_CYPHER =
      """
      MATCH (e:AnomalyEvent)
      WHERE e.t_window_start >= datetime($tStart)
        AND e.t_window_start <  datetime($tEnd)
      RETURN e.host_ip AS host, e.t_window_start AS window_start, e.score AS score,
             e.alpha1_norm AS a1, e.alpha2 AS a2, e.alpha3 AS a3
      """;

  private final Driver driver;

  public GraphPredictionsCollector(Driver driver) {
    this.driver = driver;
  }

  public Set<ScoredHostWindow> fetchRaw(OffsetDateTime periodStart, OffsetDateTime periodEnd) {
    try (Session session = driver.session()) {
      return session.executeRead(
          tx -> {
            var result =
                tx.run(
                    FETCH_EVENTS_CYPHER,
                    Map.of("tStart", periodStart.toString(), "tEnd", periodEnd.toString()));
            Set<ScoredHostWindow> events = new HashSet<>();
            while (result.hasNext()) {
              var rec = result.next();
              events.add(
                  new ScoredHostWindow(
                      rec.get("host").asString(),
                      rec.get("window_start").asOffsetDateTime(),
                      rec.get("score").asDouble(),
                      rec.get("a1").asDouble(),
                      rec.get("a2").asDouble(),
                      rec.get("a3").asDouble()));
            }
            log.info("Fetched {} raw graph events", events.size());
            return events;
          });
    }
  }

  /** Re-score raw events with given weights; return only those exceeding {@code thetaA}. */
  public Set<HostWindow> applyConfig(
      Set<ScoredHostWindow> rawEvents, double w1, double w2, double w3, double thetaA) {
    Set<HostWindow> predicted = new HashSet<>();
    for (ScoredHostWindow e : rawEvents) {
      double score = w1 * e.alpha1() + w2 * e.alpha2() + w3 * e.alpha3();
      if (score > thetaA) {
        predicted.add(new HostWindow(e.host(), e.windowStart()));
      }
    }
    return predicted;
  }

  public record ScoredHostWindow(
      String host,
      OffsetDateTime windowStart,
      double score,
      double alpha1,
      double alpha2,
      double alpha3) {}
}
