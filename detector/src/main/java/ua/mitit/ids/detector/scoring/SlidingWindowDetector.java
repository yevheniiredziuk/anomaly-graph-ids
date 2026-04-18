package ua.mitit.ids.detector.scoring;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ua.mitit.ids.detector.baseline.GdsProjectionManager;

/**
 * Main detection loop — Algorithm 4.2 of the article.
 *
 * <p>For each window [t, t+Δt): project → α₁ → α₂ → α₃ → composite score + record events → roll
 * state → drop projection. State rollover is critical; without it α₂ and α₃ would always be 0 after
 * the first window.
 */
@Service
public class SlidingWindowDetector {

  private static final Logger log = LoggerFactory.getLogger(SlidingWindowDetector.class);

  private final GdsProjectionManager projectionManager;
  private final Alpha1Computer alpha1;
  private final Alpha2Computer alpha2;
  private final Alpha3Computer alpha3;
  private final CompositeScorer scorer;
  private final WeightsConfig weights;

  @Value("${detector.window-size-minutes:5}")
  private int windowSizeMinutes;

  @Value("${detector.step-size-minutes:5}")
  private int stepSizeMinutes;

  @Value("${detector.sampling-size:1000}")
  private int samplingSize;

  @Value("${detector.sampling-seed:42}")
  private long samplingSeed;

  @Value("${detector.louvain-seed:42}")
  private long louvainSeed;

  public SlidingWindowDetector(
      GdsProjectionManager projectionManager,
      Alpha1Computer alpha1,
      Alpha2Computer alpha2,
      Alpha3Computer alpha3,
      CompositeScorer scorer,
      WeightsConfig weights) {
    this.projectionManager = projectionManager;
    this.alpha1 = alpha1;
    this.alpha2 = alpha2;
    this.alpha3 = alpha3;
    this.scorer = scorer;
    this.weights = weights;
  }

  public DetectionRun run(OffsetDateTime periodStart, OffsetDateTime periodEnd) {
    weights.validate();
    log.info(
        "Starting detection over [{}, {}) with Δt={}min, δt={}min",
        periodStart,
        periodEnd,
        windowSizeMinutes,
        stepSizeMinutes);
    log.info(
        "Weights: w1={}, w2={}, w3={}, θ_A={}",
        weights.getW1(),
        weights.getW2(),
        weights.getW3(),
        weights.getThetaA());

    Instant overallStart = Instant.now();
    int windowsProcessed = 0;
    int windowsEmpty = 0;
    long totalAnomalousEvents = 0;
    List<WindowResult> perWindowResults = new ArrayList<>();

    Duration windowSize = Duration.ofMinutes(windowSizeMinutes);
    Duration step = Duration.ofMinutes(stepSizeMinutes);

    OffsetDateTime t = periodStart;
    while (t.plus(windowSize).isBefore(periodEnd) || t.plus(windowSize).isEqual(periodEnd)) {
      OffsetDateTime tEnd = t.plus(windowSize);
      WindowResult wr = processWindow(t, tEnd);
      perWindowResults.add(wr);
      if (wr.skipped()) {
        windowsEmpty++;
      } else {
        windowsProcessed++;
        totalAnomalousEvents += wr.eventsCreated();
      }
      int done = windowsProcessed + windowsEmpty;
      if (done > 0 && done % 20 == 0) {
        log.info(
            "  Progress: {} windows done ({} processed, {} empty), {} events so far",
            done,
            windowsProcessed,
            windowsEmpty,
            totalAnomalousEvents);
      }
      t = t.plus(step);
    }

    Duration elapsed = Duration.between(overallStart, Instant.now());
    log.info(
        "Detection complete: {} windows processed, {} empty, {} anomaly events, elapsed {} ms",
        windowsProcessed,
        windowsEmpty,
        totalAnomalousEvents,
        elapsed.toMillis());

    return new DetectionRun(
        windowsProcessed, windowsEmpty, totalAnomalousEvents, elapsed, perWindowResults);
  }

  private WindowResult processWindow(OffsetDateTime tStart, OffsetDateTime tEnd) {
    try (var projection = projectionManager.createWindowProjection(tStart, tEnd)) {
      if (projection.isEmpty()) {
        return new WindowResult(tStart, tEnd, true, 0L, 0L);
      }
      alpha1.compute(projection.name(), samplingSize, samplingSeed);
      alpha2.compute(projection.name(), tStart, tEnd, louvainSeed);
      alpha3.compute(tStart, tEnd);

      CompositeScorer.ScoreResult sr = scorer.score(tStart, tEnd);

      alpha2.rollState();
      alpha3.rollState();

      return new WindowResult(tStart, tEnd, false, sr.anomalousHosts(), sr.eventsCreated());
    }
  }

  public record WindowResult(
      OffsetDateTime tStart,
      OffsetDateTime tEnd,
      boolean skipped,
      long anomalousHosts,
      long eventsCreated) {}

  public record DetectionRun(
      int windowsProcessed,
      int windowsEmpty,
      long totalAnomalousEvents,
      Duration totalDuration,
      List<WindowResult> perWindowResults) {}
}
