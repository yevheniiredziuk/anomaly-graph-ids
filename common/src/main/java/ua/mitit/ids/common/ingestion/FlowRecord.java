package ua.mitit.ids.common.ingestion;

import java.time.OffsetDateTime;

/**
 * Canonical representation of a single network flow record after preprocessing.
 *
 * <p>This is the contract between the CSV produced by {@code prepare_dataset.py} (T03/T04) and the
 * ETL services (T06 for PostgreSQL, T07 for Neo4j). Fields mirror the PostgreSQL schema from {@code
 * baseline/sql/init/01-schema.sql}.
 *
 * <p>Deliberately immutable: instances pass through batch pipelines and must be thread-safe.
 * Numeric widths sized per CICIDS2017 max observed values plus safety margin.
 */
public record FlowRecord(
    String sourceIp,
    int sourcePort,
    String destinationIp,
    int destinationPort,
    short protocol,
    OffsetDateTime tStart,
    OffsetDateTime tEnd,
    long flowDurationUs,
    long bytesFwd,
    long bytesBwd,
    long packetsFwd,
    long packetsBwd,
    int synCount,
    int rstCount,
    int pshCount,
    int ackCount,
    int finCount,
    int urgCount,
    String label) {

  /**
   * Validates the record. Throws IllegalArgumentException on invariant violation. Called by the
   * loader on every parsed row as a first-line defence against malformed CSV data.
   */
  public void validate() {
    if (sourceIp == null || sourceIp.isBlank()) {
      throw new IllegalArgumentException("sourceIp blank");
    }
    if (destinationIp == null || destinationIp.isBlank()) {
      throw new IllegalArgumentException("destinationIp blank");
    }
    if (sourcePort < 0 || sourcePort > 65535) {
      throw new IllegalArgumentException("sourcePort out of range: " + sourcePort);
    }
    if (destinationPort < 0 || destinationPort > 65535) {
      throw new IllegalArgumentException("destinationPort out of range: " + destinationPort);
    }
    if (tStart == null || tEnd == null) {
      throw new IllegalArgumentException("timestamps null");
    }
    if (tEnd.isBefore(tStart)) {
      throw new IllegalArgumentException("tEnd before tStart");
    }
    if (bytesFwd < 0 || bytesBwd < 0 || packetsFwd < 0 || packetsBwd < 0) {
      throw new IllegalArgumentException("negative volume counter");
    }
  }
}
