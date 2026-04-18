package ua.mitit.ids.evaluation.search;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ua.mitit.ids.evaluation.EvaluationService;
import ua.mitit.ids.evaluation.collectors.BaselinePredictionsCollector;
import ua.mitit.ids.evaluation.groundtruth.GroundTruthBuilder;
import ua.mitit.ids.evaluation.groundtruth.GroundTruthBuilder.HostWindow;

/**
 * Grid search for baseline PL/pgSQL thresholds.
 *
 * <p>Unlike graph search (re-scoring in-memory), each combination requires re-running detector
 * functions against flows — expensive (~15 s per run × 27 configs ≈ 7 min). Full grid 3×3×3.
 */
@Service
public class BaselineGridSearch {

  private static final Logger log = LoggerFactory.getLogger(BaselineGridSearch.class);

  private static final int[] PORT_THRESHOLDS = {30, 50, 100};
  private static final int[] BRUTE_THRESHOLDS = {50, 100, 200};
  private static final int[] DOS_THRESHOLDS = {500, 1000, 2000};

  private final JdbcTemplate pgJdbc;
  private final BaselinePredictionsCollector collector;
  private final EvaluationService evaluator;

  public BaselineGridSearch(
      JdbcTemplate pgJdbc, BaselinePredictionsCollector collector, EvaluationService evaluator) {
    this.pgJdbc = pgJdbc;
    this.collector = collector;
    this.evaluator = evaluator;
  }

  public BaselineSearchResult search(
      OffsetDateTime valPeriodStart,
      OffsetDateTime valPeriodEnd,
      int windowSizeMinutes,
      GroundTruthBuilder gtBuilder) {
    var groundTruth = gtBuilder.build(valPeriodStart, valPeriodEnd, windowSizeMinutes);
    var universe = evaluator.buildEvaluablePairs(valPeriodStart, valPeriodEnd, windowSizeMinutes);

    List<ConfigResult> results = new ArrayList<>();

    for (int portT : PORT_THRESHOLDS) {
      for (int bruteT : BRUTE_THRESHOLDS) {
        for (int dosT : DOS_THRESHOLDS) {
          log.info("Running baseline with port={}, brute={}, dos={}", portT, bruteT, dosT);
          runAllDetectors(valPeriodStart, valPeriodEnd, windowSizeMinutes, portT, bruteT, dosT);
          Set<HostWindow> predicted = collector.fetchAll(valPeriodStart, valPeriodEnd);
          var r = evaluator.evaluate(predicted, groundTruth, universe);
          results.add(
              new ConfigResult(
                  portT,
                  bruteT,
                  dosT,
                  r.overall().f1(),
                  r.overall().precision(),
                  r.overall().recall()));
          log.info(
              "  → F1={}, P={}, R={}",
              String.format("%.4f", r.overall().f1()),
              String.format("%.4f", r.overall().precision()),
              String.format("%.4f", r.overall().recall()));
        }
      }
    }

    results.sort(Comparator.comparingDouble(ConfigResult::f1).reversed());
    ConfigResult best = results.get(0);
    log.info(
        "Baseline grid search best: port={}, brute={}, dos={} → F1={}, P={}, R={}",
        best.portThreshold(),
        best.bruteThreshold(),
        best.dosThreshold(),
        String.format("%.4f", best.f1()),
        String.format("%.4f", best.precision()),
        String.format("%.4f", best.recall()));
    return new BaselineSearchResult(best, results);
  }

  private void runAllDetectors(
      OffsetDateTime start,
      OffsetDateTime end,
      int windowMinutes,
      int portT,
      int bruteT,
      int dosT) {
    pgJdbc.update("TRUNCATE baseline_detections");

    Duration step = Duration.ofMinutes(windowMinutes);
    OffsetDateTime t = start;
    while (!t.plus(step).isAfter(end)) {
      OffsetDateTime tEnd = t.plus(step);
      // queryForList reads and discards the set-returning function output;
      // INSERT side-effect executes inside the function body.
      // Multi-column set-returning functions: use queryForList(sql, args) which
      // returns List<Map<String, Object>> and discards rows. The INSERT side-effect
      // inside the PL/pgSQL function runs during execution.
      pgJdbc.queryForList("SELECT * FROM detect_port_scan(?, ?, ?)", t, tEnd, portT);
      pgJdbc.queryForList("SELECT * FROM detect_brute_force(?, ?, ?, 0.7)", t, tEnd, bruteT);
      pgJdbc.queryForList("SELECT * FROM detect_dos_flood(?, ?, ?, 0.7)", t, tEnd, dosT);
      t = tEnd;
    }
  }

  public record ConfigResult(
      int portThreshold,
      int bruteThreshold,
      int dosThreshold,
      double f1,
      double precision,
      double recall) {}

  public record BaselineSearchResult(ConfigResult best, List<ConfigResult> all) {}
}
