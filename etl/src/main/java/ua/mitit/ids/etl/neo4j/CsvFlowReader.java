package ua.mitit.ids.etl.neo4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import ua.mitit.ids.common.ingestion.FlowEdge;

/**
 * Streams aggregated edges from the CSV produced by {@code prepare_dataset.py} (T03/T04).
 *
 * <p>Memory-efficient: rows are read lazily via Apache Commons CSV's streaming API. The full
 * CICIDS2017 aggregated CSV is ~40 MB; streaming lets the loader handle gigabyte inputs with
 * constant memory.
 *
 * <p>Expected CSV columns (from {@code aggregate_flows_to_edges}):
 *
 * <pre>
 *   Source IP, Destination IP, Protocol, time_bucket,
 *   t_start, t_end, src_port_first, dst_port_first,
 *   bytes_fwd, bytes_bwd, packets_fwd, packets_bwd,
 *   syn_count, rst_count, psh_count, ack_count, fin_count,
 *   flow_count, Label
 * </pre>
 */
public class CsvFlowReader implements AutoCloseable {

  private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

  private final BufferedReader reader;
  private final CSVParser parser;

  public CsvFlowReader(Path csvPath) throws IOException {
    this.reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
    this.parser =
        CSVFormat.DEFAULT
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build()
            .parse(reader);
  }

  /**
   * Returns a lazily-consumed stream of edges. The caller is responsible for closing the stream
   * (use {@code try-with-resources} on {@link CsvFlowReader}).
   */
  public Stream<FlowEdge> stream() {
    Iterator<FlowEdge> iterator =
        new Iterator<>() {
          private final Iterator<CSVRecord> records = parser.iterator();

          @Override
          public boolean hasNext() {
            return records.hasNext();
          }

          @Override
          public FlowEdge next() {
            if (!records.hasNext()) {
              throw new NoSuchElementException();
            }
            return toEdge(records.next());
          }
        };
    return StreamSupport.stream(
        Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false);
  }

  private FlowEdge toEdge(CSVRecord record) {
    try {
      return new FlowEdge(
          record.get("Source IP"),
          record.get("Destination IP"),
          Short.parseShort(record.get("Protocol")),
          parseTimestamp(record.get("t_start")),
          parseTimestamp(record.get("t_end")),
          parseLongTolerant(record.get("bytes_fwd")),
          parseLongTolerant(record.get("bytes_bwd")),
          parseLongTolerant(record.get("packets_fwd")),
          parseLongTolerant(record.get("packets_bwd")),
          parseLongTolerant(record.get("flow_count")),
          record.get("Label"));
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to parse CSV row at line " + record.getRecordNumber() + ": " + record.toMap(), e);
    }
  }

  /**
   * {@code prepare_dataset.py} aggregates counters via pandas {@code sum()}, which promotes int64
   * source columns to float64 when any input row is float. The resulting CSV writes "258.0" rather
   * than "258". Accept both forms.
   */
  private static long parseLongTolerant(String s) {
    if (s.indexOf('.') < 0) {
      return Long.parseLong(s);
    }
    return (long) Double.parseDouble(s);
  }

  private static OffsetDateTime parseTimestamp(String s) {
    // prepare_dataset.py writes ISO format "2017-07-04T09:00:00+00:00".
    // Treat bare timestamps as UTC (fallback for synthetic inputs).
    if (!s.contains("+") && !s.contains("Z")) {
      return OffsetDateTime.parse(s + "+00:00", TS_FORMAT);
    }
    return OffsetDateTime.parse(s, TS_FORMAT);
  }

  @Override
  public void close() throws IOException {
    parser.close();
    reader.close();
  }
}
