package ua.mitit.ids.detector.baseline;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import ua.mitit.ids.detector.DetectorApplication;

/**
 * CLI entry point for baseline computation (T09).
 *
 * <p>Usage:
 *
 * <pre>
 *   (cd detector && ../mvnw spring-boot:run \
 *       -Dspring-boot.run.main-class=ua.mitit.ids.detector.baseline.BaselineCliApplication \
 *       -Dspring-boot.run.arguments="--start=2017-07-03T00:00:00Z --end=2017-07-04T00:00:00Z")
 * </pre>
 *
 * <p>Plain POJO (not {@code @SpringBootApplication}) to prevent accidental {@code
 * CommandLineRunner} firing during {@code DetectorApplicationTests} context load.
 */
public final class BaselineCliApplication {

  private static final Logger log = LoggerFactory.getLogger(BaselineCliApplication.class);

  private BaselineCliApplication() {}

  public static void main(String[] args) {
    OffsetDateTime start = OffsetDateTime.parse("2017-07-03T00:00:00Z");
    OffsetDateTime end = OffsetDateTime.parse("2017-07-04T00:00:00Z");
    for (String arg : args) {
      if (arg.startsWith("--start=")) {
        start = OffsetDateTime.parse(arg.substring("--start=".length()));
      } else if (arg.startsWith("--end=")) {
        end = OffsetDateTime.parse(arg.substring("--end=".length()));
      }
    }
    log.info("Running baseline computation for [{}, {})", start, end);

    SpringApplication app = new SpringApplication(DetectorApplication.class);
    app.setWebApplicationType(WebApplicationType.NONE);

    int exit = 0;
    try (ConfigurableApplicationContext ctx = app.run()) {
      BaselineProfileService service = ctx.getBean(BaselineProfileService.class);
      BaselineProfileService.BaselineResult result = service.compute(start, end);

      log.info(
          "Baseline complete: snapshots processed={}, empty={}, "
              + "hosts with μ/σ={}, hosts below minN={}, elapsed={} ms",
          result.snapshotsProcessed(),
          result.snapshotsEmpty(),
          result.hostsWithSufficientSamples(),
          result.hostsBelowThreshold(),
          result.totalDuration().toMillis());
    } catch (Exception e) {
      log.error("Baseline computation failed", e);
      exit = 1;
    }
    System.exit(exit);
  }
}
