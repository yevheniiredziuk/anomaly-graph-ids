package ua.mitit.ids.detector.baseline;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Computes baseline betweenness-centrality statistics (μ, σ) per Host by sampling BC across sliding
 * snapshots of the benign Monday traffic (Algorithm of Section 4.3).
 *
 * <p>Hosts observed in fewer than {@code minSamplesPerHost} snapshots get {@code baseline_bc_std =
 * null} (signalling to the detector that they cannot be z-scored).
 *
 * <p>Reproducibility: {@code samplingSeed} is fixed (default 42) — repeated runs over the same data
 * yield identical values.
 */
@Service
public class BaselineProfileService {

  private static final Logger log = LoggerFactory.getLogger(BaselineProfileService.class);

  private static final String BC_STREAM_CYPHER =
      """
      CALL gds.betweenness.stream($graphName, {
          samplingSize: $samplingSize,
          samplingSeed: $samplingSeed
      })
      YIELD nodeId, score
      RETURN gds.util.asNode(nodeId).ip AS ip, score
      """;

  private static final String WRITE_BASELINE_CYPHER =
      """
      UNWIND $rows AS row
      MATCH (h:Host {ip: row.ip})
      SET h.baseline_bc_mean = row.mean,
          h.baseline_bc_std  = row.std,
          h.baseline_samples = row.n,
          h.baseline_computed_at = datetime()
      """;

  private final Driver driver;
  private final GdsProjectionManager projectionManager;

  @Value("${baseline.snapshot-duration-minutes:5}")
  private int snapshotDurationMinutes;

  @Value("${baseline.sampling-size:1000}")
  private int samplingSize;

  @Value("${baseline.sampling-seed:42}")
  private long samplingSeed;

  @Value("${baseline.min-samples-per-host:3}")
  private int minSamplesPerHost;

  public BaselineProfileService(Driver driver, GdsProjectionManager projectionManager) {
    this.driver = driver;
    this.projectionManager = projectionManager;
  }

  public BaselineResult compute(OffsetDateTime periodStart, OffsetDateTime periodEnd) {
    log.info(
        "Computing baseline for period [{}, {}) with snapshot duration {} min",
        periodStart,
        periodEnd,
        snapshotDurationMinutes);
    Instant overallStart = Instant.now();

    Duration snapshotDuration = Duration.ofMinutes(snapshotDurationMinutes);
    Map<String, List<Double>> accumulator = new HashMap<>();
    int snapshotsProcessed = 0;
    int snapshotsEmpty = 0;

    OffsetDateTime t = periodStart;
    while (t.isBefore(periodEnd)) {
      OffsetDateTime tEnd = t.plus(snapshotDuration);
      if (tEnd.isAfter(periodEnd)) {
        tEnd = periodEnd;
      }

      try (var projection = projectionManager.createWindowProjection(t, tEnd)) {
        if (projection.isEmpty()) {
          log.debug("Empty snapshot [{}, {}), skipping", t, tEnd);
          snapshotsEmpty++;
        } else {
          List<HostScore> scores = runBetweenness(projection.name());
          for (HostScore hs : scores) {
            accumulator.computeIfAbsent(hs.ip(), k -> new ArrayList<>()).add(hs.score());
          }
          snapshotsProcessed++;
          if (snapshotsProcessed % 10 == 0) {
            log.info(
                "  Processed {} snapshots ({} hosts tracked so far)",
                snapshotsProcessed,
                accumulator.size());
          }
        }
      }

      t = tEnd;
    }

    log.info(
        "Done: {} snapshots processed, {} empty; {} unique hosts seen",
        snapshotsProcessed,
        snapshotsEmpty,
        accumulator.size());

    List<Map<String, Object>> rowsWithStats = new ArrayList<>();
    List<Map<String, Object>> rowsBelowThreshold = new ArrayList<>();
    int hostsWithSufficientSamples = 0;
    int hostsBelowThreshold = 0;

    for (Map.Entry<String, List<Double>> e : accumulator.entrySet()) {
      List<Double> samples = e.getValue();
      int n = samples.size();
      if (n < minSamplesPerHost) {
        // Write explicit NULL for std to signal "cannot z-score". Use a separate
        // UNWIND batch because mixing NULL and typed values in one Map rejects serialisation.
        rowsBelowThreshold.add(Map.of("ip", e.getKey(), "n", n));
        hostsBelowThreshold++;
        continue;
      }
      double mean = samples.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
      double variance = samples.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / (n - 1);
      double std = Math.sqrt(variance);
      rowsWithStats.add(Map.of("ip", e.getKey(), "mean", mean, "std", std, "n", n));
      hostsWithSufficientSamples++;
    }

    writeBaselineWithStats(rowsWithStats);
    writeBaselineBelowThreshold(rowsBelowThreshold);

    Duration elapsed = Duration.between(overallStart, Instant.now());
    log.info(
        "Baseline written: {} hosts with μ/σ, {} hosts below threshold (n < {})",
        hostsWithSufficientSamples,
        hostsBelowThreshold,
        minSamplesPerHost);
    log.info("Total elapsed: {} ms", elapsed.toMillis());

    return new BaselineResult(
        snapshotsProcessed,
        snapshotsEmpty,
        hostsWithSufficientSamples,
        hostsBelowThreshold,
        elapsed);
  }

  private List<HostScore> runBetweenness(String graphName) {
    try (Session session = driver.session()) {
      return session.executeWrite(
          tx -> {
            var result =
                tx.run(
                    BC_STREAM_CYPHER,
                    Map.of(
                        "graphName", graphName,
                        "samplingSize", samplingSize,
                        "samplingSeed", samplingSeed));
            List<HostScore> list = new ArrayList<>();
            while (result.hasNext()) {
              var rec = result.next();
              list.add(new HostScore(rec.get("ip").asString(), rec.get("score").asDouble()));
            }
            return list;
          });
    }
  }

  private void writeBaselineWithStats(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) {
      return;
    }
    try (Session session = driver.session()) {
      session.executeWriteWithoutResult(tx -> tx.run(WRITE_BASELINE_CYPHER, Map.of("rows", rows)));
    }
  }

  /** Separate path: null std signals "insufficient samples — cannot z-score". */
  private void writeBaselineBelowThreshold(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) {
      return;
    }
    try (Session session = driver.session()) {
      session.executeWriteWithoutResult(
          tx ->
              tx.run(
                  """
                  UNWIND $rows AS row
                  MATCH (h:Host {ip: row.ip})
                  SET h.baseline_bc_mean = 0.0,
                      h.baseline_bc_std  = null,
                      h.baseline_samples = row.n,
                      h.baseline_computed_at = datetime()
                  """,
                  Map.of("rows", rows)));
    }
  }

  public record HostScore(String ip, double score) {}

  public record BaselineResult(
      int snapshotsProcessed,
      int snapshotsEmpty,
      int hostsWithSufficientSamples,
      int hostsBelowThreshold,
      Duration totalDuration) {}
}
