package ua.mitit.ids.evaluation.metrics;

import java.util.Set;
import ua.mitit.ids.evaluation.groundtruth.GroundTruthBuilder.HostWindow;

/**
 * Per-(host, window) confusion matrix.
 *
 * <p>Closed-world: any pair in {@code allEvaluatedPairs} that is NOT in {@code predicted} is a
 * "benign prediction"; any pair NOT in {@code groundTruth} is "benign truth". TN is derived by
 * subtraction.
 */
public record ConfusionMatrix(long tp, long fp, long fn, long tn) {

  public static ConfusionMatrix from(
      Set<HostWindow> predicted, Set<HostWindow> groundTruth, Set<HostWindow> allEvaluatedPairs) {
    long tp = predicted.stream().filter(groundTruth::contains).count();
    long fp = predicted.size() - tp;
    long fn = groundTruth.stream().filter(hw -> !predicted.contains(hw)).count();
    long tn = allEvaluatedPairs.size() - tp - fp - fn;
    return new ConfusionMatrix(tp, fp, fn, Math.max(0, tn));
  }

  public double precision() {
    long denom = tp + fp;
    return denom == 0 ? 0.0 : (double) tp / denom;
  }

  public double recall() {
    long denom = tp + fn;
    return denom == 0 ? 0.0 : (double) tp / denom;
  }

  public double f1() {
    double p = precision();
    double r = recall();
    return (p + r == 0.0) ? 0.0 : 2.0 * p * r / (p + r);
  }

  public double accuracy() {
    long total = tp + fp + fn + tn;
    return total == 0 ? 0.0 : (double) (tp + tn) / total;
  }
}
