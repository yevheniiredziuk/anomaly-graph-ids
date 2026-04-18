package ua.mitit.ids.detector.scoring;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import ua.mitit.ids.detector.DetectorApplication;

/**
 * CLI entry point for the sliding-window detector (T10-T13).
 *
 * <p>Usage:
 *
 * <pre>
 *   (cd detector && ../mvnw spring-boot:run \
 *       -Dspring-boot.run.main-class=ua.mitit.ids.detector.scoring.DetectorCliApplication \
 *       -Dspring-boot.run.arguments="--start=2017-07-04T00:00:00Z --end=2017-07-06T00:00:00Z")
 * </pre>
 *
 * <p>Default period is Tuesday + Wednesday of CICIDS2017. POJO pattern (same as the baseline CLI) —
 * prevents accidental run during {@code DetectorApplicationTests} context load.
 */
public final class DetectorCliApplication {

  private static final Logger log = LoggerFactory.getLogger(DetectorCliApplication.class);

  private DetectorCliApplication() {}

  public static void main(String[] args) {
    OffsetDateTime start = OffsetDateTime.parse("2017-07-04T00:00:00Z");
    OffsetDateTime end = OffsetDateTime.parse("2017-07-06T00:00:00Z");
    for (String arg : args) {
      if (arg.startsWith("--start=")) {
        start = OffsetDateTime.parse(arg.substring("--start=".length()));
      } else if (arg.startsWith("--end=")) {
        end = OffsetDateTime.parse(arg.substring("--end=".length()));
      }
    }

    SpringApplication app = new SpringApplication(DetectorApplication.class);
    app.setWebApplicationType(WebApplicationType.NONE);

    int exit = 0;
    try (ConfigurableApplicationContext ctx = app.run()) {
      SlidingWindowDetector detector = ctx.getBean(SlidingWindowDetector.class);
      SlidingWindowDetector.DetectionRun result = detector.run(start, end);

      log.info(
          "Detection run summary: windows processed={}, empty={}, events={}, elapsed={} ms, "
              + "avg per window={} ms",
          result.windowsProcessed(),
          result.windowsEmpty(),
          result.totalAnomalousEvents(),
          result.totalDuration().toMillis(),
          result.windowsProcessed() == 0
              ? 0
              : result.totalDuration().toMillis() / result.windowsProcessed());
    } catch (Exception e) {
      log.error("Detector run failed", e);
      exit = 1;
    }
    System.exit(exit);
  }
}
