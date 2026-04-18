package ua.mitit.ids.detector.baseline;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Helper for creating and disposing in-memory GDS graph projections.
 *
 * <p>GDS projections consume memory in the Neo4j process; they MUST be explicitly dropped after
 * use. Try-with-resources on {@link ProjectionHandle} guarantees cleanup even on exception.
 *
 * <p>Cypher projection (not native) is required because we filter edges by time window.
 */
@Component
public class GdsProjectionManager {

  private static final Logger log = LoggerFactory.getLogger(GdsProjectionManager.class);

  private static final String PROJECT_CYPHER =
      """
      CALL gds.graph.project.cypher(
          $name,
          'MATCH (h:Host) RETURN id(h) AS id',
          'MATCH (h1:Host)-[r:CONNECTS_TO]->(h2:Host)
           WHERE r.start_time >= datetime($tStart)
             AND r.start_time <  datetime($tEnd)
           RETURN id(h1) AS source, id(h2) AS target, 1 AS weight',
          { parameters: { tStart: $tStart, tEnd: $tEnd } }
      )
      YIELD graphName, nodeCount, relationshipCount
      RETURN graphName, nodeCount, relationshipCount
      """;

  private static final String DROP_CYPHER =
      "CALL gds.graph.drop($name, false) YIELD graphName RETURN graphName";

  private final Driver driver;

  public GdsProjectionManager(Driver driver) {
    this.driver = driver;
  }

  public ProjectionHandle createWindowProjection(OffsetDateTime tStart, OffsetDateTime tEnd) {
    String name = "win_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    try (Session session = driver.session()) {
      var record =
          session.executeWrite(
              tx ->
                  tx.run(
                          PROJECT_CYPHER,
                          Map.of(
                              "name", name,
                              "tStart", tStart.toString(),
                              "tEnd", tEnd.toString()))
                      .single());
      long nodes = record.get("nodeCount").asLong();
      long rels = record.get("relationshipCount").asLong();
      log.debug("Created projection {} ({} nodes, {} relationships)", name, nodes, rels);
      return new ProjectionHandle(name, nodes, rels);
    }
  }

  public void drop(String name) {
    try (Session session = driver.session()) {
      session.executeWriteWithoutResult(tx -> tx.run(DROP_CYPHER, Map.of("name", name)));
      log.debug("Dropped projection {}", name);
    } catch (Exception e) {
      log.warn("Failed to drop projection {}: {}", name, e.getMessage());
    }
  }

  /** AutoCloseable handle — guarantees projection cleanup. */
  public class ProjectionHandle implements AutoCloseable {
    private final String name;
    private final long nodeCount;
    private final long relationshipCount;
    private boolean closed = false;

    ProjectionHandle(String name, long nodeCount, long relationshipCount) {
      this.name = name;
      this.nodeCount = nodeCount;
      this.relationshipCount = relationshipCount;
    }

    public String name() {
      return name;
    }

    public long nodeCount() {
      return nodeCount;
    }

    public long relationshipCount() {
      return relationshipCount;
    }

    public boolean isEmpty() {
      return relationshipCount == 0;
    }

    @Override
    public void close() {
      if (!closed) {
        drop(name);
        closed = true;
      }
    }
  }
}
