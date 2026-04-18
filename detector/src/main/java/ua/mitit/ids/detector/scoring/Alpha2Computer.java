package ua.mitit.ids.detector.scoring;

import java.time.OffsetDateTime;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Computes α₂ — community membership change weighted by intra-community edge ratio (ρ) — formulas
 * (3.8)/(3.9) from the article (Variant B).
 *
 * <p>State machine: α₂ requires two windows to have a meaningful value. In the first window {@code
 * previous_community} is NULL and α₂ is forced to 0. From the second window onwards the rule {@code
 * 𝟙[C_t ≠ C_{t-δt}] · ρ_prev} fires.
 */
@Service
public class Alpha2Computer {

  private static final Logger log = LoggerFactory.getLogger(Alpha2Computer.class);

  // GDS 2026.03 rejects `randomSeed` as an Unknown config key for Louvain.
  // We get determinism instead via `concurrency: 1` (single-threaded execution
  // is deterministic without needing an explicit seed).
  private static final String LOUVAIN_WRITE_CYPHER =
      """
      CALL gds.louvain.stream($graphName, {
          maxIterations: 10,
          tolerance: 0.0001,
          concurrency: 1
      })
      YIELD nodeId, communityId
      WITH gds.util.asNode(nodeId) AS h, communityId
      SET h.current_community = communityId
      RETURN count(h) AS hosts_assigned
      """;

  private static final String COMPUTE_RHO_CYPHER =
      """
      MATCH (v:Host)
      WHERE v.current_community IS NOT NULL
      OPTIONAL MATCH (v)-[r:CONNECTS_TO]-(u:Host)
      WHERE r.start_time >= datetime($tStart)
        AND r.start_time <  datetime($tEnd)
        AND u.current_community IS NOT NULL
      WITH v,
           count(r) AS total_deg,
           sum(CASE WHEN u.current_community = v.current_community THEN 1 ELSE 0 END) AS intra_deg
      SET v.current_rho = CASE
          WHEN total_deg = 0 THEN 0.0
          ELSE toFloat(intra_deg) / total_deg
      END
      RETURN count(v) AS hosts_with_rho
      """;

  private static final String COMPUTE_ALPHA2_CYPHER =
      """
      MATCH (v:Host)
      WHERE v.current_community IS NOT NULL
      SET v.current_alpha2 = CASE
          WHEN v.previous_community IS NULL
              THEN 0.0
          WHEN v.current_community <> v.previous_community
              THEN coalesce(v.previous_rho, 0.0)
          ELSE 0.0
      END
      RETURN count(v) AS hosts_with_alpha2
      """;

  private static final String ROLL_STATE_CYPHER =
      """
      MATCH (v:Host)
      WHERE v.current_community IS NOT NULL
      SET v.previous_community = v.current_community,
          v.previous_rho = v.current_rho
      """;

  private final Driver driver;

  public Alpha2Computer(Driver driver) {
    this.driver = driver;
  }

  public void compute(String graphName, OffsetDateTime tStart, OffsetDateTime tEnd, long seed) {
    // `seed` kept in signature for API stability; GDS 2026.03 Louvain no longer accepts it.
    try (Session session = driver.session()) {
      session.executeWriteWithoutResult(
          tx -> tx.run(LOUVAIN_WRITE_CYPHER, Map.of("graphName", graphName)));
      session.executeWriteWithoutResult(
          tx ->
              tx.run(
                  COMPUTE_RHO_CYPHER,
                  Map.of("tStart", tStart.toString(), "tEnd", tEnd.toString())));
      session.executeWriteWithoutResult(tx -> tx.run(COMPUTE_ALPHA2_CYPHER));
    }
    log.debug("α₂: computed for window [{}, {})", tStart, tEnd);
  }

  /** Rolls {@code current_community/rho → previous_*}. Call after CompositeScorer. */
  public void rollState() {
    try (Session session = driver.session()) {
      session.executeWriteWithoutResult(tx -> tx.run(ROLL_STATE_CYPHER));
    }
  }
}
