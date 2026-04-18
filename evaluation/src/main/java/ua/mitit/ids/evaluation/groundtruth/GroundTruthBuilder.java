package ua.mitit.ids.evaluation.groundtruth;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Builds the per-(host, window) ground truth set from PostgreSQL {@code flows.label}.
 *
 * <p>Bucketing: a flow with {@code label != 'BENIGN'} marks BOTH endpoints (source_ip,
 * destination_ip) anomalous in its time bucket. This is the conservative "victim + attacker both
 * interesting" choice.
 */
@Component
public class GroundTruthBuilder {

  private static final Logger log = LoggerFactory.getLogger(GroundTruthBuilder.class);

  private final JdbcTemplate pgJdbc;

  public GroundTruthBuilder(JdbcTemplate pgJdbc) {
    this.pgJdbc = pgJdbc;
  }

  public GroundTruth build(
      OffsetDateTime periodStart, OffsetDateTime periodEnd, int windowSizeMinutes) {
    log.info(
        "Building ground truth for [{}, {}) with {}-min windows",
        periodStart,
        periodEnd,
        windowSizeMinutes);

    String sql =
        """
        WITH bucketed AS (
            SELECT
                source_ip      AS src_ip,
                destination_ip AS dst_ip,
                to_timestamp(
                    (extract(epoch from t_start)::bigint / (? * 60)) * (? * 60)
                ) AT TIME ZONE 'UTC' AS window_start,
                label
            FROM flows
            WHERE t_start >= ? AND t_start < ?
              AND label <> 'BENIGN'
        )
        SELECT src_ip AS host, window_start, label FROM bucketed
        UNION ALL
        SELECT dst_ip AS host, window_start, label FROM bucketed
        """;

    Set<HostWindow> anomalousPairs = new HashSet<>();
    Map<String, Set<HostWindow>> byAttackType = new HashMap<>();

    pgJdbc.query(
        sql,
        rs -> {
          String host = rs.getString("host");
          OffsetDateTime windowStart = rs.getObject("window_start", OffsetDateTime.class);
          String label = rs.getString("label");
          HostWindow hw = new HostWindow(host, windowStart);
          anomalousPairs.add(hw);
          byAttackType.computeIfAbsent(label, k -> new HashSet<>()).add(hw);
        },
        windowSizeMinutes,
        windowSizeMinutes,
        periodStart,
        periodEnd);

    log.info(
        "Ground truth: {} total (host, window) anomalous pairs across {} attack types",
        anomalousPairs.size(),
        byAttackType.size());
    byAttackType.forEach((label, set) -> log.info("  {}: {} pairs", label, set.size()));

    return new GroundTruth(anomalousPairs, byAttackType);
  }

  public record HostWindow(String host, OffsetDateTime windowStart) {}

  public record GroundTruth(Set<HostWindow> anomalous, Map<String, Set<HostWindow>> byAttackType) {}
}
