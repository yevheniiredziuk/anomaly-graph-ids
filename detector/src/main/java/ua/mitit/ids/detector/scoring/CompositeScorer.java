package ua.mitit.ids.detector.scoring;

import java.time.OffsetDateTime;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Composes α₁, α₂, α₃ into A(v,t) and records decision D(v,t) — formula (3.12).
 *
 * <pre>
 *   A(v,t) = w₁ · α̃₁ + w₂ · α₂ + w₃ · α₃
 *   D(v,t) = anomalous iff A(v,t) > θ_A
 * </pre>
 *
 * <p>Writes {@code current_anomaly_score} and boolean {@code current_decision} on every Host.
 * Anomalous hosts also get a persistent {@code :AnomalyEvent} audit node for the window.
 */
@Service
public class CompositeScorer {

  private static final Logger log = LoggerFactory.getLogger(CompositeScorer.class);

  private static final String SCORE_CYPHER =
      """
      MATCH (v:Host)
      WHERE v.current_alpha3 IS NOT NULL
      WITH v,
           coalesce(v.current_alpha1_norm, 0.0) AS a1n,
           coalesce(v.current_alpha2,      0.0) AS a2,
           coalesce(v.current_alpha3,      0.0) AS a3
      WITH v, $w1 * a1n + $w2 * a2 + $w3 * a3 AS score
      SET v.current_anomaly_score = score,
          v.current_decision = score > $thetaA
      RETURN
          count(v) AS total_hosts,
          sum(CASE WHEN score > $thetaA THEN 1 ELSE 0 END) AS anomalous
      """;

  private static final String RECORD_EVENTS_CYPHER =
      """
      MATCH (v:Host)
      WHERE v.current_decision = true
      CREATE (e:AnomalyEvent {
          host_ip: v.ip,
          t_window_start: datetime($tStart),
          t_window_end: datetime($tEnd),
          score: v.current_anomaly_score,
          alpha1_norm: coalesce(v.current_alpha1_norm, 0.0),
          alpha2: coalesce(v.current_alpha2, 0.0),
          alpha3: coalesce(v.current_alpha3, 0.0),
          detected_at: datetime()
      })
      RETURN count(e) AS events_created
      """;

  private final Driver driver;
  private final WeightsConfig weights;

  public CompositeScorer(Driver driver, WeightsConfig weights) {
    this.driver = driver;
    this.weights = weights;
  }

  public ScoreResult score(OffsetDateTime tStart, OffsetDateTime tEnd) {
    try (Session session = driver.session()) {
      long[] stats =
          session.executeWrite(
              tx -> {
                var r =
                    tx.run(
                            SCORE_CYPHER,
                            Map.of(
                                "w1", weights.getW1(),
                                "w2", weights.getW2(),
                                "w3", weights.getW3(),
                                "thetaA", weights.getThetaA()))
                        .single();
                return new long[] {
                  r.get("total_hosts").asLong(), r.get("anomalous").asLong(),
                };
              });

      long eventsCreated =
          session.executeWrite(
              tx ->
                  tx.run(
                          RECORD_EVENTS_CYPHER,
                          Map.of("tStart", tStart.toString(), "tEnd", tEnd.toString()))
                      .single()
                      .get("events_created")
                      .asLong());

      if (stats[1] > 0) {
        log.debug(
            "Window [{}, {}): {}/{} hosts flagged anomalous → {} events recorded",
            tStart,
            tEnd,
            stats[1],
            stats[0],
            eventsCreated);
      }
      return new ScoreResult(stats[0], stats[1], eventsCreated);
    }
  }

  public record ScoreResult(long totalHosts, long anomalousHosts, long eventsCreated) {}
}
