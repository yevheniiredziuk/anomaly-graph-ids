package ua.mitit.ids.evaluation.collectors;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ua.mitit.ids.evaluation.groundtruth.GroundTruthBuilder.HostWindow;

/**
 * Reads baseline (PL/pgSQL) predictions from {@code baseline_detections} and expands each row into
 * {(src_ip, window), (dst_ip, window)} pairs. {@code dst_ip} is NULL for port_scan rows.
 */
@Component
public class BaselinePredictionsCollector {

  private static final Logger log = LoggerFactory.getLogger(BaselinePredictionsCollector.class);

  private final JdbcTemplate pgJdbc;

  public BaselinePredictionsCollector(JdbcTemplate pgJdbc) {
    this.pgJdbc = pgJdbc;
  }

  public Set<HostWindow> fetchAll(OffsetDateTime periodStart, OffsetDateTime periodEnd) {
    String sql =
        """
        SELECT src_ip, dst_ip, t_window_start
        FROM baseline_detections
        WHERE t_window_start >= ? AND t_window_start < ?
        """;
    Set<HostWindow> predicted = new HashSet<>();
    pgJdbc.query(
        sql,
        rs -> {
          String src = rs.getString("src_ip");
          String dst = rs.getString("dst_ip");
          OffsetDateTime window = rs.getObject("t_window_start", OffsetDateTime.class);
          if (src != null) predicted.add(new HostWindow(src, window));
          if (dst != null) predicted.add(new HostWindow(dst, window));
        },
        periodStart,
        periodEnd);
    log.info("Fetched {} (host, window) predictions from baseline_detections", predicted.size());
    return predicted;
  }
}
