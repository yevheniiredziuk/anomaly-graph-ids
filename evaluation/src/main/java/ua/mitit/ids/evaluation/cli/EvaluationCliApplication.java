package ua.mitit.ids.evaluation.cli;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import ua.mitit.ids.evaluation.EvaluationApplication;
import ua.mitit.ids.evaluation.EvaluationService;
import ua.mitit.ids.evaluation.collectors.BaselinePredictionsCollector;
import ua.mitit.ids.evaluation.collectors.GraphPredictionsCollector;
import ua.mitit.ids.evaluation.export.CsvExporter;
import ua.mitit.ids.evaluation.groundtruth.GroundTruthBuilder;
import ua.mitit.ids.evaluation.search.BaselineGridSearch;
import ua.mitit.ids.evaluation.search.GraphGridSearch;

/**
 * POJO CLI — boots {@link EvaluationApplication} context then dispatches by {@code --mode}.
 *
 * <pre>
 *   --mode=evaluate-graph       P/R/F1 for graph method (uses --w1/--w2/--w3/--thetaA)
 *   --mode=evaluate-baseline    P/R/F1 for baseline_detections (already populated)
 *   --mode=grid-search-graph    Grid search over weights + threshold
 *   --mode=grid-search-baseline Grid search over PL/pgSQL thresholds (slow)
 *   --mode=compare              Both methods side-by-side
 * Common: --start --end --window
 * Graph: --w1 --w2 --w3 --thetaA
 * </pre>
 */
public final class EvaluationCliApplication {

  private static final Logger log = LoggerFactory.getLogger(EvaluationCliApplication.class);

  private EvaluationCliApplication() {}

  public static void main(String[] args) {
    String mode = "compare";
    OffsetDateTime start = OffsetDateTime.parse("2017-07-04T00:00:00Z");
    OffsetDateTime end = OffsetDateTime.parse("2017-07-06T00:00:00Z");
    int windowMinutes = 5;
    double w1 = 0.4, w2 = 0.3, w3 = 0.3, thetaA = 0.5;

    for (String arg : args) {
      if (arg.startsWith("--mode=")) mode = arg.substring("--mode=".length());
      else if (arg.startsWith("--start="))
        start = OffsetDateTime.parse(arg.substring("--start=".length()));
      else if (arg.startsWith("--end="))
        end = OffsetDateTime.parse(arg.substring("--end=".length()));
      else if (arg.startsWith("--window="))
        windowMinutes = Integer.parseInt(arg.substring("--window=".length()));
      else if (arg.startsWith("--w1=")) w1 = Double.parseDouble(arg.substring("--w1=".length()));
      else if (arg.startsWith("--w2=")) w2 = Double.parseDouble(arg.substring("--w2=".length()));
      else if (arg.startsWith("--w3=")) w3 = Double.parseDouble(arg.substring("--w3=".length()));
      else if (arg.startsWith("--thetaA="))
        thetaA = Double.parseDouble(arg.substring("--thetaA=".length()));
    }

    SpringApplication app = new SpringApplication(EvaluationApplication.class);
    app.setWebApplicationType(WebApplicationType.NONE);

    int exit = 0;
    try (ConfigurableApplicationContext ctx = app.run()) {
      GroundTruthBuilder gtBuilder = ctx.getBean(GroundTruthBuilder.class);
      EvaluationService evaluator = ctx.getBean(EvaluationService.class);
      GraphPredictionsCollector graphCollector = ctx.getBean(GraphPredictionsCollector.class);
      BaselinePredictionsCollector baselineCollector =
          ctx.getBean(BaselinePredictionsCollector.class);
      GraphGridSearch graphGridSearch = ctx.getBean(GraphGridSearch.class);
      BaselineGridSearch baselineGridSearch = ctx.getBean(BaselineGridSearch.class);
      CsvExporter csvExporter = ctx.getBean(CsvExporter.class);

      switch (mode) {
        case "evaluate-graph" ->
            evaluateGraph(
                gtBuilder,
                evaluator,
                graphCollector,
                start,
                end,
                windowMinutes,
                w1,
                w2,
                w3,
                thetaA);
        case "evaluate-baseline" ->
            evaluateBaseline(gtBuilder, evaluator, baselineCollector, start, end, windowMinutes);
        case "grid-search-graph" -> graphGridSearch.search(start, end, windowMinutes, gtBuilder);
        case "grid-search-baseline" ->
            baselineGridSearch.search(start, end, windowMinutes, gtBuilder);
        case "compare" -> {
          log.info(
              "=== Compare: graph w1={}, w2={}, w3={}, θA={} vs baseline ===", w1, w2, w3, thetaA);
          evaluateGraph(
              gtBuilder, evaluator, graphCollector, start, end, windowMinutes, w1, w2, w3, thetaA);
          evaluateBaseline(gtBuilder, evaluator, baselineCollector, start, end, windowMinutes);
        }
        case "export-roc" ->
            csvExporter.exportRocData(
                start, end, windowMinutes, w1, w2, w3, Path.of("results/roc_data.csv"));
        case "export-per-attack" ->
            csvExporter.exportPerAttackRecall(
                start,
                end,
                windowMinutes,
                w1,
                w2,
                w3,
                thetaA,
                Path.of("results/per_attack_recall.csv"));
        case "export-simplex" -> {
          var gsResult = graphGridSearch.search(start, end, windowMinutes, gtBuilder);
          csvExporter.exportWeightSimplex(gsResult.all(), Path.of("results/weight_simplex.csv"));
        }
        default -> throw new IllegalArgumentException("Unknown mode: " + mode);
      }
    } catch (Exception e) {
      log.error("Evaluation failed", e);
      exit = 1;
    }
    System.exit(exit);
  }

  private static void evaluateGraph(
      GroundTruthBuilder gtBuilder,
      EvaluationService evaluator,
      GraphPredictionsCollector graphCollector,
      OffsetDateTime start,
      OffsetDateTime end,
      int window,
      double w1,
      double w2,
      double w3,
      double thetaA) {
    var gt = gtBuilder.build(start, end, window);
    var universe = evaluator.buildEvaluablePairs(start, end, window);
    var raw = graphCollector.fetchRaw(start, end);
    var predicted = graphCollector.applyConfig(raw, w1, w2, w3, thetaA);
    var result = evaluator.evaluate(predicted, gt, universe);
    printResult("GRAPH", result);
  }

  private static void evaluateBaseline(
      GroundTruthBuilder gtBuilder,
      EvaluationService evaluator,
      BaselinePredictionsCollector baselineCollector,
      OffsetDateTime start,
      OffsetDateTime end,
      int window) {
    var gt = gtBuilder.build(start, end, window);
    var universe = evaluator.buildEvaluablePairs(start, end, window);
    var predicted = baselineCollector.fetchAll(start, end);
    var result = evaluator.evaluate(predicted, gt, universe);
    printResult("BASELINE", result);
  }

  private static void printResult(String label, EvaluationService.EvaluationResult r) {
    var cm = r.overall();
    log.info(
        "========== {} ==========  TP={} FP={} FN={} TN={}  P={}  R={}  F1={}  Acc={}",
        label,
        cm.tp(),
        cm.fp(),
        cm.fn(),
        cm.tn(),
        String.format("%.4f", cm.precision()),
        String.format("%.4f", cm.recall()),
        String.format("%.4f", cm.f1()),
        String.format("%.4f", cm.accuracy()));
    log.info("Per-attack-type Recall:");
    r.perAttackRecall().forEach((k, v) -> log.info("  {} = {}", k, String.format("%.4f", v)));
  }
}
