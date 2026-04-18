package ua.mitit.ids.etl.neo4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.exceptions.TransientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ua.mitit.ids.common.ingestion.FlowEdge;

/**
 * Loads aggregated edges into Neo4j via UNWIND batches.
 *
 * <p>Strategy:
 *
 * <ul>
 *   <li>Stream edges from CSV (constant memory)
 *   <li>Accumulate into batches of {@code DEFAULT_BATCH_SIZE} (5000)
 *   <li>Each batch is one Bolt transaction with a parameterised UNWIND query
 *   <li>Transient failures retried with exponential backoff
 * </ul>
 *
 * <p>Why UNWIND batch 5000? Measured throughput sweet spot: single-transaction is network-bound
 * (~100/s); batch 1000 ~15 k/s; batch 5000 ~30 k/s (diminishing returns); batch 20 000 risks
 * transaction timeout on slower machines.
 *
 * <p>Schema assumption: V001 migration applied ({@code host_ip_unique} constraint, range index on
 * {@code CONNECTS_TO.start_time}). Without it the load still works but slower, with risk of
 * duplicate {@code Host} nodes.
 */
@Service
public class Neo4jUnwindLoader {

  private static final Logger log = LoggerFactory.getLogger(Neo4jUnwindLoader.class);

  private static final String UNWIND_CYPHER =
      """
      UNWIND $edges AS e
      MERGE (src:Host {ip: e.src_ip})
        ON CREATE SET src.first_seen = e.start_time
      SET src.last_seen = CASE
          WHEN src.last_seen IS NULL OR e.end_time > src.last_seen
            THEN e.end_time
            ELSE src.last_seen
        END
      MERGE (dst:Host {ip: e.dst_ip})
        ON CREATE SET dst.first_seen = e.start_time
      SET dst.last_seen = CASE
          WHEN dst.last_seen IS NULL OR e.end_time > dst.last_seen
            THEN e.end_time
            ELSE dst.last_seen
        END
      CREATE (src)-[r:CONNECTS_TO {
          start_time:  e.start_time,
          end_time:    e.end_time,
          protocol:    e.protocol,
          bytes_fwd:   e.bytes_fwd,
          bytes_bwd:   e.bytes_bwd,
          packets_fwd: e.packets_fwd,
          packets_bwd: e.packets_bwd,
          flow_count:  e.flow_count,
          label:       e.label
      }]->(dst)
      """;

  // CALL { WITH n ... } IN TRANSACTIONS is the 4.4+-compatible form. The scoped-variable
  // form CALL (n) { ... } is 5.6+ only — we stick to the older syntax for portability.
  private static final String WIPE_CYPHER =
      "MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS";

  private static final int DEFAULT_BATCH_SIZE = 5000;
  private static final int MAX_RETRIES = 3;
  private static final long INITIAL_BACKOFF_MS = 500;

  private final Driver driver;

  public Neo4jUnwindLoader(Driver driver) {
    this.driver = driver;
  }

  public LoadResult load(Path csvPath, boolean wipeFirst) throws IOException {
    log.info("Loading edges from {} into Neo4j...", csvPath);
    Instant start = Instant.now();

    if (wipeFirst) {
      wipeGraph();
    }

    long totalEdges = 0;
    long totalBatches = 0;

    try (CsvFlowReader reader = new CsvFlowReader(csvPath);
        Stream<FlowEdge> edges = reader.stream()) {
      List<Map<String, Object>> batch = new ArrayList<>(DEFAULT_BATCH_SIZE);
      for (FlowEdge edge : (Iterable<FlowEdge>) edges::iterator) {
        edge.validate();
        batch.add(edge.toMap());
        if (batch.size() >= DEFAULT_BATCH_SIZE) {
          flushBatchWithRetry(batch);
          totalEdges += batch.size();
          totalBatches++;
          if (totalBatches % 10 == 0) {
            log.info("  Loaded {} edges in {} batches...", totalEdges, totalBatches);
          }
          batch = new ArrayList<>(DEFAULT_BATCH_SIZE);
        }
      }
      if (!batch.isEmpty()) {
        flushBatchWithRetry(batch);
        totalEdges += batch.size();
        totalBatches++;
      }
    }

    Duration elapsed = Duration.between(start, Instant.now());
    log.info(
        "Loaded {} edges in {} batches, total time {} ms ({} edges/sec)",
        totalEdges,
        totalBatches,
        elapsed.toMillis(),
        totalEdges * 1000 / Math.max(1, elapsed.toMillis()));

    return new LoadResult(totalEdges, totalBatches, elapsed);
  }

  private void flushBatchWithRetry(List<Map<String, Object>> batch) {
    int attempt = 0;
    long backoffMs = INITIAL_BACKOFF_MS;
    while (true) {
      try (Session session = driver.session(SessionConfig.defaultConfig())) {
        session.executeWriteWithoutResult(tx -> tx.run(UNWIND_CYPHER, Map.of("edges", batch)));
        return;
      } catch (TransientException e) {
        attempt++;
        if (attempt >= MAX_RETRIES) {
          log.error("Batch failed after {} attempts: {}", MAX_RETRIES, e.getMessage());
          throw e;
        }
        log.warn(
            "Transient failure on batch (attempt {}/{}), retrying in {} ms: {}",
            attempt,
            MAX_RETRIES,
            backoffMs,
            e.getMessage());
        try {
          Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(ie);
        }
        backoffMs *= 2;
      }
    }
  }

  private void wipeGraph() {
    log.info("Wiping graph (DETACH DELETE all nodes)...");
    try (Session session = driver.session()) {
      // IN TRANSACTIONS subqueries must run via session.run (implicit/auto-commit),
      // not inside an explicit transaction — server error otherwise.
      session.run(WIPE_CYPHER).consume();
    } catch (Exception e) {
      log.warn(
          "IN TRANSACTIONS wipe failed ({}); falling back to single DETACH DELETE", e.getMessage());
      try (Session session = driver.session()) {
        session.executeWriteWithoutResult(tx -> tx.run("MATCH (n) DETACH DELETE n"));
      }
    }
  }

  public record LoadResult(long totalEdges, long totalBatches, Duration totalDuration) {
    public double throughputEdgesPerSec() {
      return totalEdges * 1000.0 / Math.max(1, totalDuration.toMillis());
    }
  }
}
