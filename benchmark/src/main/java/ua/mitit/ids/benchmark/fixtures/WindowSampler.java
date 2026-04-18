package ua.mitit.ids.benchmark.fixtures;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Rolling window provider: each invocation gets a different {@link TimeWindow}. Prevents query
 * cache from inflating p50 by returning identical plan/result rows.
 *
 * <p>Covers Tue 09:00 → Wed 17:00, 30-min step, 5-min windows ≈ 64 distinct windows.
 */
@State(Scope.Benchmark)
public class WindowSampler {

  public List<TimeWindow> windows;
  private final AtomicInteger cursor = new AtomicInteger(0);

  @Setup(Level.Trial)
  public void setup() {
    // Business-hours windows only. Night-time windows in CICIDS2017 have zero
    // traffic, which makes GDS Cypher projection empty (0 nodes) and crashes
    // gds.betweenness.stream with "bound must be positive" when the sampler
    // tries to pick from an empty node set.
    windows = new ArrayList<>();
    Duration windowSize = Duration.ofMinutes(5);
    Duration step = Duration.ofMinutes(30);
    for (OffsetDateTime dayStart :
        new OffsetDateTime[] {
          OffsetDateTime.parse("2017-07-04T09:00:00Z"),
          OffsetDateTime.parse("2017-07-05T09:00:00Z"),
        }) {
      OffsetDateTime t = dayStart;
      OffsetDateTime end = dayStart.plus(Duration.ofHours(8));
      while (t.isBefore(end)) {
        windows.add(new TimeWindow(t, t.plus(windowSize)));
        t = t.plus(step);
      }
    }
  }

  public TimeWindow next() {
    int idx = cursor.getAndIncrement() % windows.size();
    return windows.get(idx);
  }

  public record TimeWindow(OffsetDateTime start, OffsetDateTime end) {}
}
