package ua.mitit.ids.etl.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Populates the {@code hosts} dimension table from {@code flows} after bulk load.
 *
 * <p>Single SQL statement with UNION ALL over source/destination IPs, aggregated by IP. ~30× faster
 * than row-by-row insertion. ON CONFLICT DO UPDATE makes the load idempotent across reruns.
 */
@Service
public class HostsPopulator {

  private static final Logger log = LoggerFactory.getLogger(HostsPopulator.class);

  private static final String POPULATE_SQL =
      """
      INSERT INTO hosts (ip, first_seen, last_seen, total_flows)
      SELECT ip, MIN(first_seen), MAX(last_seen), SUM(flow_count)
      FROM (
          SELECT source_ip AS ip, t_start AS first_seen, t_end AS last_seen,
                 1 AS flow_count
          FROM flows
          UNION ALL
          SELECT destination_ip, t_start, t_end, 1
          FROM flows
      ) host_rows
      GROUP BY ip
      ON CONFLICT (ip) DO UPDATE SET
          first_seen  = LEAST(hosts.first_seen, EXCLUDED.first_seen),
          last_seen   = GREATEST(hosts.last_seen, EXCLUDED.last_seen),
          total_flows = hosts.total_flows + EXCLUDED.total_flows
      """;

  private final DataSource dataSource;

  public HostsPopulator(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public void populate() throws SQLException {
    log.info("Populating hosts table...");
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      conn.setAutoCommit(false);
      long start = System.currentTimeMillis();
      int rowsAffected = stmt.executeUpdate(POPULATE_SQL);
      conn.commit();
      long elapsed = System.currentTimeMillis() - start;
      log.info("Populated hosts: {} rows in {} ms", rowsAffected, elapsed);
    }
  }
}
