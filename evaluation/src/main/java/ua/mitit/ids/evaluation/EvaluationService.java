package ua.mitit.ids.evaluation;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ua.mitit.ids.evaluation.groundtruth.GroundTruthBuilder;
import ua.mitit.ids.evaluation.groundtruth.GroundTruthBuilder.HostWindow;
import ua.mitit.ids.evaluation.metrics.ConfusionMatrix;

/** Joins predictions + ground truth → ConfusionMatrix + per-attack Recall. */
@Service
public class EvaluationService {

  private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

  private final JdbcTemplate pgJdbc;

  public EvaluationService(JdbcTemplate pgJdbc) {
    this.pgJdbc = pgJdbc;
  }

  /**
   * Universe of (host, window) pairs for TN computation: all pairs where the host is seen as source
   * or destination in any flow in the window.
   */
  public Set<HostWindow> buildEvaluablePairs(
      OffsetDateTime periodStart, OffsetDateTime periodEnd, int windowSizeMinutes) {
    String sql =
        """
        SELECT DISTINCT host, window_start FROM (
            SELECT source_ip AS host,
                to_timestamp((extract(epoch from t_start)::bigint / (? * 60)) * (? * 60))
                    AT TIME ZONE 'UTC' AS window_start
            FROM flows WHERE t_start >= ? AND t_start < ?
            UNION
            SELECT destination_ip AS host,
                to_timestamp((extract(epoch from t_start)::bigint / (? * 60)) * (? * 60))
                    AT TIME ZONE 'UTC' AS window_start
            FROM flows WHERE t_start >= ? AND t_start < ?
        ) u
        """;
    Set<HostWindow> pairs = new HashSet<>();
    pgJdbc.query(
        sql,
        rs -> {
          pairs.add(
              new HostWindow(
                  rs.getString("host"), rs.getObject("window_start", OffsetDateTime.class)));
        },
        windowSizeMinutes,
        windowSizeMinutes,
        periodStart,
        periodEnd,
        windowSizeMinutes,
        windowSizeMinutes,
        periodStart,
        periodEnd);
    log.info("Evaluable universe: {} (host, window) pairs", pairs.size());
    return pairs;
  }

  public EvaluationResult evaluate(
      Set<HostWindow> predicted,
      GroundTruthBuilder.GroundTruth groundTruth,
      Set<HostWindow> universe) {
    ConfusionMatrix overall = ConfusionMatrix.from(predicted, groundTruth.anomalous(), universe);

    Map<String, Double> perAttackRecall = new HashMap<>();
    for (var entry : groundTruth.byAttackType().entrySet()) {
      Set<HostWindow> truthsOfType = entry.getValue();
      long tp = truthsOfType.stream().filter(predicted::contains).count();
      long fn = truthsOfType.size() - tp;
      double recall = (tp + fn == 0) ? 0.0 : (double) tp / (tp + fn);
      perAttackRecall.put(entry.getKey(), recall);
    }
    return new EvaluationResult(overall, perAttackRecall);
  }

  public record EvaluationResult(ConfusionMatrix overall, Map<String, Double> perAttackRecall) {}
}
