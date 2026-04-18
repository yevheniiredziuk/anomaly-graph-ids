package ua.mitit.ids.evaluation.search;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ua.mitit.ids.evaluation.EvaluationService;
import ua.mitit.ids.evaluation.collectors.GraphPredictionsCollector;
import ua.mitit.ids.evaluation.collectors.GraphPredictionsCollector.ScoredHostWindow;
import ua.mitit.ids.evaluation.groundtruth.GroundTruthBuilder;
import ua.mitit.ids.evaluation.groundtruth.GroundTruthBuilder.HostWindow;

/**
 * Grid search over graph-method weights (simplex w1+w2+w3=1, step 0.1) and θ_A ∈ {0.2..0.7}.
 * Fetches raw events once, then re-scores in memory per config.
 */
@Service
public class GraphGridSearch {

  private static final Logger log = LoggerFactory.getLogger(GraphGridSearch.class);

  private static final double[] WEIGHT_VALUES = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8};
  private static final double[] THETA_A_VALUES = {0.2, 0.3, 0.4, 0.5, 0.6, 0.7};

  private final GraphPredictionsCollector collector;
  private final EvaluationService evaluator;

  public GraphGridSearch(GraphPredictionsCollector collector, EvaluationService evaluator) {
    this.collector = collector;
    this.evaluator = evaluator;
  }

  public GridSearchResult search(
      OffsetDateTime valPeriodStart,
      OffsetDateTime valPeriodEnd,
      int windowSizeMinutes,
      GroundTruthBuilder gtBuilder) {
    log.info("Loading raw events for grid search...");
    Set<ScoredHostWindow> rawEvents = collector.fetchRaw(valPeriodStart, valPeriodEnd);
    var groundTruth = gtBuilder.build(valPeriodStart, valPeriodEnd, windowSizeMinutes);
    var universe = evaluator.buildEvaluablePairs(valPeriodStart, valPeriodEnd, windowSizeMinutes);

    List<WeightTriple> weightCandidates = enumerateSimplex();
    log.info(
        "Grid search: {} weight simplex points × {} thresholds = {} configs",
        weightCandidates.size(),
        THETA_A_VALUES.length,
        weightCandidates.size() * THETA_A_VALUES.length);

    ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
    List<ConfigResult> results;
    try {
      results =
          pool.submit(
                  () ->
                      weightCandidates.parallelStream()
                          .flatMap(
                              wt ->
                                  IntStream.range(0, THETA_A_VALUES.length)
                                      .mapToObj(
                                          ti -> {
                                            double thetaA = THETA_A_VALUES[ti];
                                            Set<HostWindow> predicted =
                                                collector.applyConfig(
                                                    rawEvents, wt.w1(), wt.w2(), wt.w3(), thetaA);
                                            var r =
                                                evaluator.evaluate(
                                                    predicted, groundTruth, universe);
                                            return new ConfigResult(
                                                wt.w1(),
                                                wt.w2(),
                                                wt.w3(),
                                                thetaA,
                                                r.overall().f1(),
                                                r.overall().precision(),
                                                r.overall().recall());
                                          }))
                          .collect(Collectors.toList()))
              .join();
    } finally {
      pool.shutdown();
    }

    results.sort(Comparator.comparingDouble(ConfigResult::f1).reversed());
    ConfigResult best = results.get(0);
    log.info(
        "Grid search best: w1={}, w2={}, w3={}, θA={} → F1={}, P={}, R={}",
        best.w1(),
        best.w2(),
        best.w3(),
        best.thetaA(),
        String.format("%.4f", best.f1()),
        String.format("%.4f", best.precision()),
        String.format("%.4f", best.recall()));
    return new GridSearchResult(best, results);
  }

  private List<WeightTriple> enumerateSimplex() {
    List<WeightTriple> result = new ArrayList<>();
    for (double w1 : WEIGHT_VALUES) {
      for (double w2 : WEIGHT_VALUES) {
        double w3 = Math.round((1.0 - w1 - w2) * 10.0) / 10.0;
        if (w3 >= 0.1 - 1e-9 && w3 <= 0.8 + 1e-9 && Math.abs((w1 + w2 + w3) - 1.0) < 1e-9) {
          result.add(new WeightTriple(w1, w2, w3));
        }
      }
    }
    return result;
  }

  public record WeightTriple(double w1, double w2, double w3) {}

  public record ConfigResult(
      double w1, double w2, double w3, double thetaA, double f1, double precision, double recall) {}

  public record GridSearchResult(ConfigResult best, List<ConfigResult> all) {}
}
