package ua.mitit.ids.detector.scoring;

import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Computes α₁ — BC z-score deviation — for all hosts in the current window and writes it as {@code
 * current_alpha1} and {@code current_alpha1_norm} on each Host.
 *
 * <p>Formula (3.7) + sigmoid normalisation (Section 3.4):
 *
 * <pre>
 *   α₁(v,t)   = |BC_t(v) - μ^BC_v| / (σ^BC_v + ε)
 *   α̃₁(v,t)  = σ(α₁(v,t) - θ₁)    where σ(x) = 1 / (1 + exp(-x))
 * </pre>
 *
 * <p>Hosts without a baseline (baseline_bc_std IS NULL) are skipped — CompositeScorer treats
 * missing α̃₁ as 0.
 */
@Service
public class Alpha1Computer {

  private static final Logger log = LoggerFactory.getLogger(Alpha1Computer.class);

  private static final String COMPUTE_AND_WRITE_CYPHER =
      """
      CALL gds.betweenness.stream($graphName, {
          samplingSize: $samplingSize,
          samplingSeed: $samplingSeed
      })
      YIELD nodeId, score AS bc
      WITH gds.util.asNode(nodeId) AS h, bc
      SET h.current_bc = bc
      WITH h, bc
      WHERE h.baseline_bc_std IS NOT NULL
      WITH h,
           abs(bc - h.baseline_bc_mean) / (h.baseline_bc_std + $epsilon) AS alpha1
      SET h.current_alpha1 = alpha1,
          h.current_alpha1_norm = 1.0 / (1.0 + exp(-(alpha1 - $theta1)))
      RETURN count(h) AS hosts_scored
      """;

  private final Driver driver;
  private final WeightsConfig weights;

  public Alpha1Computer(Driver driver, WeightsConfig weights) {
    this.driver = driver;
    this.weights = weights;
  }

  public long compute(String graphName, int samplingSize, long samplingSeed) {
    try (Session session = driver.session()) {
      long hostsScored =
          session.executeWrite(
              tx ->
                  tx.run(
                          COMPUTE_AND_WRITE_CYPHER,
                          Map.of(
                              "graphName", graphName,
                              "samplingSize", samplingSize,
                              "samplingSeed", samplingSeed,
                              "epsilon", weights.getEpsilon(),
                              "theta1", weights.getTheta1()))
                      .single()
                      .get("hosts_scored")
                      .asLong());
      log.debug("α₁: scored {} hosts with baseline", hostsScored);
      return hostsScored;
    }
  }
}
