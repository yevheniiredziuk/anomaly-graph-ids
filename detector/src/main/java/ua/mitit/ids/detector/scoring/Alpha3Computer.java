package ua.mitit.ids.detector.scoring;

import java.time.OffsetDateTime;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Computes α₃ — Jaccard drift of 2-hop neighborhood — per formula (3.11).
 *
 * <pre>
 *   α₃(v,t) = 1 - J(N₂^t(v), N₂^{t-δt}(v))
 *           = 1 - |intersect| / |union|
 * </pre>
 *
 * <p>Uses pure Cypher: we cannot reuse {@code gds.nodeSimilarity} because we need (a) 2-hop and (b)
 * set-based Jaccard on actual IPs stored across windows.
 *
 * <p>Uses inclusion-exclusion for union size instead of {@code apoc.coll.union} so this works
 * without APOC installed.
 */
@Service
public class Alpha3Computer {

  private static final Logger log = LoggerFactory.getLogger(Alpha3Computer.class);

  private static final String COMPUTE_NEIGHBORHOOD_CYPHER =
      """
      MATCH (v:Host)
      OPTIONAL MATCH (v)-[r1:CONNECTS_TO]-(u:Host)
      WHERE r1.start_time >= datetime($tStart) AND r1.start_time < datetime($tEnd)
      WITH v, collect(DISTINCT u.ip) AS one_hop
      OPTIONAL MATCH (v)-[r2:CONNECTS_TO]-(:Host)-[r3:CONNECTS_TO]-(w:Host)
      WHERE r2.start_time >= datetime($tStart) AND r2.start_time < datetime($tEnd)
        AND r3.start_time >= datetime($tStart) AND r3.start_time < datetime($tEnd)
        AND w.ip <> v.ip
      WITH v, one_hop, collect(DISTINCT w.ip) AS two_hop
      WITH v, [ip IN one_hop + two_hop WHERE ip IS NOT NULL AND ip <> v.ip] AS combined
      SET v.current_neighborhood_2hop = apoc.coll.toSet(combined)
      RETURN count(v) AS hosts_updated
      """;

  // Fallback form (no APOC) — inclusion-exclusion for union size.
  private static final String COMPUTE_NEIGHBORHOOD_NO_APOC_CYPHER =
      """
      MATCH (v:Host)
      OPTIONAL MATCH (v)-[r1:CONNECTS_TO]-(u:Host)
      WHERE r1.start_time >= datetime($tStart) AND r1.start_time < datetime($tEnd)
      WITH v, collect(DISTINCT u.ip) AS one_hop
      OPTIONAL MATCH (v)-[r2:CONNECTS_TO]-(:Host)-[r3:CONNECTS_TO]-(w:Host)
      WHERE r2.start_time >= datetime($tStart) AND r2.start_time < datetime($tEnd)
        AND r3.start_time >= datetime($tStart) AND r3.start_time < datetime($tEnd)
        AND w.ip <> v.ip
      WITH v, one_hop, collect(DISTINCT w.ip) AS two_hop
      WITH v, [ip IN one_hop + two_hop WHERE ip IS NOT NULL AND ip <> v.ip] AS combined
      WITH v, [x IN combined WHERE NOT x IN [y IN combined WHERE y IS NULL] ] AS combined_clean
      SET v.current_neighborhood_2hop =
          reduce(acc = [], x IN combined_clean |
                 CASE WHEN x IN acc THEN acc ELSE acc + [x] END)
      RETURN count(v) AS hosts_updated
      """;

  private static final String COMPUTE_ALPHA3_CYPHER =
      """
      MATCH (v:Host)
      WHERE v.current_neighborhood_2hop IS NOT NULL
      WITH v,
           coalesce(v.previous_neighborhood_2hop, []) AS prev,
           v.current_neighborhood_2hop AS curr
      WITH v, prev, curr,
           size([x IN curr WHERE x IN prev]) AS intersect_size,
           size(curr) + size(prev) AS combined_size
      WITH v, intersect_size, combined_size - intersect_size AS union_size
      SET v.current_alpha3 = CASE
          WHEN union_size = 0 THEN 0.0
          ELSE 1.0 - (toFloat(intersect_size) / union_size)
      END
      RETURN count(v) AS hosts_with_alpha3
      """;

  private static final String ROLL_STATE_CYPHER =
      """
      MATCH (v:Host)
      SET v.previous_neighborhood_2hop = v.current_neighborhood_2hop
      """;

  private final Driver driver;
  private volatile Boolean apocAvailable = null;

  public Alpha3Computer(Driver driver) {
    this.driver = driver;
  }

  public void compute(OffsetDateTime tStart, OffsetDateTime tEnd) {
    boolean useApoc = checkApocOnce();
    String neighborhoodCypher =
        useApoc ? COMPUTE_NEIGHBORHOOD_CYPHER : COMPUTE_NEIGHBORHOOD_NO_APOC_CYPHER;
    try (Session session = driver.session()) {
      final String cypher = neighborhoodCypher;
      session.executeWriteWithoutResult(
          tx -> tx.run(cypher, Map.of("tStart", tStart.toString(), "tEnd", tEnd.toString())));
      session.executeWriteWithoutResult(tx -> tx.run(COMPUTE_ALPHA3_CYPHER));
    }
    log.debug("α₃: computed for window [{}, {})", tStart, tEnd);
  }

  public void rollState() {
    try (Session session = driver.session()) {
      session.executeWriteWithoutResult(tx -> tx.run(ROLL_STATE_CYPHER));
    }
  }

  private boolean checkApocOnce() {
    if (apocAvailable != null) {
      return apocAvailable;
    }
    synchronized (this) {
      if (apocAvailable != null) {
        return apocAvailable;
      }
      try (Session session = driver.session()) {
        session.executeRead(tx -> tx.run("RETURN apoc.coll.toSet([1,1,2]) AS s").single().get("s"));
        apocAvailable = true;
      } catch (Exception e) {
        log.info("APOC not available, using pure-Cypher fallback for neighborhood deduplication");
        apocAvailable = false;
      }
    }
    return apocAvailable;
  }
}
