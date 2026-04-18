package ua.mitit.ids.common.ingestion;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Aggregated flow edge — unit of ingestion for Neo4j (super-edge :CONNECTS_TO in the temporal
 * multigraph).
 *
 * <p>Differs from {@link FlowRecord} (T06, raw flow for PostgreSQL):
 *
 * <ul>
 *   <li>Aggregated by {@code (src_ip, dst_ip, protocol, time_bucket_60s)} per Section 4.2 of the
 *       article
 *   <li>Counters are sums across the merged flows
 *   <li>Single {@code label} resolved via "any-attack wins" during preprocessing
 * </ul>
 *
 * <p>Field names in {@link #toMap()} match the parameter keys inside the UNWIND Cypher statement in
 * {@code Neo4jUnwindLoader}.
 */
public record FlowEdge(
    String srcIp,
    String dstIp,
    short protocol,
    OffsetDateTime tStart,
    OffsetDateTime tEnd,
    long bytesFwd,
    long bytesBwd,
    long packetsFwd,
    long packetsBwd,
    long flowCount,
    String label) {

  /**
   * Serialises to a form consumable by the Neo4j driver as an element of the {@code $edges} list
   * parameter. {@code OffsetDateTime} maps to Cypher {@code DATETIME} natively (Neo4j driver 5+).
   */
  public Map<String, Object> toMap() {
    return Map.ofEntries(
        Map.entry("src_ip", srcIp),
        Map.entry("dst_ip", dstIp),
        Map.entry("protocol", (int) protocol),
        Map.entry("start_time", tStart),
        Map.entry("end_time", tEnd),
        Map.entry("bytes_fwd", bytesFwd),
        Map.entry("bytes_bwd", bytesBwd),
        Map.entry("packets_fwd", packetsFwd),
        Map.entry("packets_bwd", packetsBwd),
        Map.entry("flow_count", flowCount),
        Map.entry("label", label));
  }

  public void validate() {
    if (srcIp == null || srcIp.isBlank()) {
      throw new IllegalArgumentException("srcIp blank");
    }
    if (dstIp == null || dstIp.isBlank()) {
      throw new IllegalArgumentException("dstIp blank");
    }
    if (tStart == null || tEnd == null) {
      throw new IllegalArgumentException("timestamps null");
    }
    if (bytesFwd < 0 || bytesBwd < 0 || packetsFwd < 0 || packetsBwd < 0) {
      throw new IllegalArgumentException("negative counters");
    }
  }
}
