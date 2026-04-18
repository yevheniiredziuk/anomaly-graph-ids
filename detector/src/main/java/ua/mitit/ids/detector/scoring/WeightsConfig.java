package ua.mitit.ids.detector.scoring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Composite anomaly-score weights and thresholds — loaded from {@code application.yml} (prefix
 * {@code detector.weights}). Defaults are mid-range starting points; T15 grid search will tune
 * them.
 */
@Component
@ConfigurationProperties(prefix = "detector.weights")
public class WeightsConfig {

  private double w1 = 0.4;
  private double w2 = 0.3;
  private double w3 = 0.3;
  private double thetaA = 0.5;
  private double theta1 = 2.0;
  private double epsilon = 1e-6;

  public double getW1() {
    return w1;
  }

  public double getW2() {
    return w2;
  }

  public double getW3() {
    return w3;
  }

  public double getThetaA() {
    return thetaA;
  }

  public double getTheta1() {
    return theta1;
  }

  public double getEpsilon() {
    return epsilon;
  }

  public void setW1(double w1) {
    this.w1 = w1;
  }

  public void setW2(double w2) {
    this.w2 = w2;
  }

  public void setW3(double w3) {
    this.w3 = w3;
  }

  public void setThetaA(double thetaA) {
    this.thetaA = thetaA;
  }

  public void setTheta1(double theta1) {
    this.theta1 = theta1;
  }

  public void setEpsilon(double epsilon) {
    this.epsilon = epsilon;
  }

  public void validate() {
    double sum = w1 + w2 + w3;
    if (Math.abs(sum - 1.0) > 0.001) {
      throw new IllegalStateException("Weights must sum to 1.0, got " + sum);
    }
    if (w1 < 0 || w2 < 0 || w3 < 0) {
      throw new IllegalStateException("Weights must be non-negative");
    }
    if (thetaA < 0 || thetaA > 1) {
      throw new IllegalStateException("thetaA must be in [0, 1]");
    }
    if (theta1 < 0) {
      throw new IllegalStateException("theta1 must be non-negative");
    }
    if (epsilon <= 0) {
      throw new IllegalStateException("epsilon must be positive");
    }
  }
}
