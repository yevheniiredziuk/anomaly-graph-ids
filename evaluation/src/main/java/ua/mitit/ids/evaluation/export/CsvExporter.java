package ua.mitit.ids.evaluation.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ua.mitit.ids.evaluation.EvaluationService;
import ua.mitit.ids.evaluation.collectors.BaselinePredictionsCollector;
import ua.mitit.ids.evaluation.collectors.GraphPredictionsCollector;
import ua.mitit.ids.evaluation.groundtruth.GroundTruthBuilder;
import ua.mitit.ids.evaluation.groundtruth.GroundTruthBuilder.HostWindow;
import ua.mitit.ids.evaluation.metrics.ConfusionMatrix;
import ua.mitit.ids.evaluation.search.GraphGridSearch;

/**
 * Exports evaluation metrics to CSV for downstream Python plotting (T18 figures).
 *
 * <p>Each export method writes a single CSV matching the schema expected by the corresponding plot
 * script (see {@code scripts/viz/plot_*.py}).
 */
@Service
public class CsvExporter {

  private static final Logger log = LoggerFactory.getLogger(CsvExporter.class);

  private final GroundTruthBuilder gtBuilder;
  private final EvaluationService evaluator;
  private final GraphPredictionsCollector graphCollector;
  private final BaselinePredictionsCollector baselineCollector;

  public CsvExporter(
      GroundTruthBuilder gtBuilder,
      EvaluationService evaluator,
      GraphPredictionsCollector graphCollector,
      BaselinePredictionsCollector baselineCollector) {
    this.gtBuilder = gtBuilder;
    this.evaluator = evaluator;
    this.graphCollector = graphCollector;
    this.baselineCollector = baselineCollector;
  }

  /**
   * ROC sweep for {@code plot_roc_curves.py}. Produces (method, attack_type, threshold, tpr, fpr)
   * rows. For the graph method we sweep {@code thetaA ∈ [0, 1]} step 0.05; the baseline is a single
   * operating point (thresholds are internal to the PL/pgSQL functions).
   */
  public void exportRocData(
      OffsetDateTime periodStart,
      OffsetDateTime periodEnd,
      int windowSizeMinutes,
      double w1,
      double w2,
      double w3,
      Path outPath)
      throws IOException {
    log.info("Exporting ROC data to {}", outPath);
    var gt = gtBuilder.build(periodStart, periodEnd, windowSizeMinutes);
    var universe = evaluator.buildEvaluablePairs(periodStart, periodEnd, windowSizeMinutes);
    var raw = graphCollector.fetchRaw(periodStart, periodEnd);

    if (outPath.getParent() != null) {
      Files.createDirectories(outPath.getParent());
    }
    try (BufferedWriter w = Files.newBufferedWriter(outPath)) {
      w.write("method,attack_type,threshold,tpr,fpr\n");
      double[] thresholds = generateThresholds();
      for (double theta : thresholds) {
        Set<HostWindow> predicted = graphCollector.applyConfig(raw, w1, w2, w3, theta);
        writeRocRow(w, "graph", "ALL", theta, predicted, gt.anomalous(), universe);
        for (var e : gt.byAttackType().entrySet()) {
          writeRocRow(w, "graph", e.getKey(), theta, predicted, e.getValue(), universe);
        }
      }
      Set<HostWindow> basePred = baselineCollector.fetchAll(periodStart, periodEnd);
      writeRocRow(w, "baseline", "ALL", 0.5, basePred, gt.anomalous(), universe);
      for (var e : gt.byAttackType().entrySet()) {
        writeRocRow(w, "baseline", e.getKey(), 0.5, basePred, e.getValue(), universe);
      }
    }
    log.info("ROC data export complete");
  }

  /** Per-attack-type recall comparison for {@code plot_per_attack_recall.py}. */
  public void exportPerAttackRecall(
      OffsetDateTime periodStart,
      OffsetDateTime periodEnd,
      int windowSizeMinutes,
      double w1,
      double w2,
      double w3,
      double thetaA,
      Path outPath)
      throws IOException {
    log.info("Exporting per-attack recall to {}", outPath);
    var gt = gtBuilder.build(periodStart, periodEnd, windowSizeMinutes);
    var universe = evaluator.buildEvaluablePairs(periodStart, periodEnd, windowSizeMinutes);
    var raw = graphCollector.fetchRaw(periodStart, periodEnd);
    var graphPred = graphCollector.applyConfig(raw, w1, w2, w3, thetaA);
    var basePred = baselineCollector.fetchAll(periodStart, periodEnd);

    var graphRes = evaluator.evaluate(graphPred, gt, universe);
    var baseRes = evaluator.evaluate(basePred, gt, universe);

    if (outPath.getParent() != null) {
      Files.createDirectories(outPath.getParent());
    }
    try (BufferedWriter w = Files.newBufferedWriter(outPath)) {
      w.write("attack_type,graph_recall,baseline_recall\n");
      for (String attack : gt.byAttackType().keySet()) {
        double g = graphRes.perAttackRecall().getOrDefault(attack, 0.0);
        double b = baseRes.perAttackRecall().getOrDefault(attack, 0.0);
        w.write(String.format(Locale.ROOT, "%s,%.4f,%.4f%n", attack, g, b));
      }
    }
  }

  /** Weight simplex heatmap input for {@code plot_weight_simplex_heatmap.py}. */
  public void exportWeightSimplex(List<GraphGridSearch.ConfigResult> configs, Path outPath)
      throws IOException {
    log.info("Exporting weight simplex ({} configs) to {}", configs.size(), outPath);
    if (outPath.getParent() != null) {
      Files.createDirectories(outPath.getParent());
    }
    try (BufferedWriter w = Files.newBufferedWriter(outPath)) {
      w.write("w1,w2,w3,thetaA,f1\n");
      for (GraphGridSearch.ConfigResult c : configs) {
        w.write(
            String.format(
                Locale.ROOT,
                "%.2f,%.2f,%.2f,%.2f,%.4f%n",
                c.w1(),
                c.w2(),
                c.w3(),
                c.thetaA(),
                c.f1()));
      }
    }
  }

  private void writeRocRow(
      BufferedWriter w,
      String method,
      String attack,
      double threshold,
      Set<HostWindow> predicted,
      Set<HostWindow> groundTruthSet,
      Set<HostWindow> universe)
      throws IOException {
    ConfusionMatrix cm = ConfusionMatrix.from(predicted, groundTruthSet, universe);
    double tpr = cm.recall();
    double fpr = (cm.fp() + cm.tn() == 0) ? 0.0 : (double) cm.fp() / (cm.fp() + cm.tn());
    w.write(
        String.format(Locale.ROOT, "%s,%s,%.4f,%.6f,%.6f%n", method, attack, threshold, tpr, fpr));
  }

  private static double[] generateThresholds() {
    double[] result = new double[21];
    for (int i = 0; i < 21; i++) {
      result[i] = i * 0.05;
    }
    return result;
  }
}
