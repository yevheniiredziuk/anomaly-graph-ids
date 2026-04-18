package ua.mitit.ids.etl.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import ua.mitit.ids.etl.EtlApplication;
import ua.mitit.ids.etl.postgres.FlowsCopyLoader;
import ua.mitit.ids.etl.postgres.HostsPopulator;

/**
 * CLI entry point for PostgreSQL ETL.
 *
 * <p>Usage:
 *
 * <pre>
 *   ./mvnw -pl etl spring-boot:run \
 *     -Dspring-boot.run.main-class=ua.mitit.ids.etl.cli.PostgresEtlCliApplication \
 *     -Dspring-boot.run.arguments="--csv=data/cleaned/flows_for_postgres.csv --truncate-first"
 * </pre>
 *
 * <p>Flags:
 *
 * <ul>
 *   <li>{@code --csv=<path>} — path to CSV (required)
 *   <li>{@code --truncate-first} — {@code TRUNCATE} table before loading (default: false)
 *   <li>{@code --skip-hosts} — skip HostsPopulator (default: false)
 * </ul>
 *
 * <p>Deliberately a plain POJO (no Spring annotations): component-scanning of {@code
 * EtlApplication} would otherwise pick this class up, and if it implemented {@code
 * CommandLineRunner} the test context would execute it on every context-load test. Here we boot the
 * {@link EtlApplication} context manually, resolve the service beans, then run the ETL
 * imperatively.
 */
public final class PostgresEtlCliApplication {

  private static final Logger log = LoggerFactory.getLogger(PostgresEtlCliApplication.class);

  private PostgresEtlCliApplication() {}

  public static void main(String[] args) {
    Args parsed = parseArgs(args);
    if (parsed.csvPath == null) {
      log.error("Missing required argument: --csv=<path>");
      System.exit(2);
      return;
    }

    SpringApplication app = new SpringApplication(EtlApplication.class);
    app.setWebApplicationType(WebApplicationType.NONE);

    int exit = 0;
    try (ConfigurableApplicationContext ctx = app.run()) {
      FlowsCopyLoader loader = ctx.getBean(FlowsCopyLoader.class);
      HostsPopulator populator = ctx.getBean(HostsPopulator.class);

      FlowsCopyLoader.LoadResult result = loader.load(parsed.csvPath, parsed.truncateFirst);

      log.info(
          "Load complete: rows={}, size={} MB, total={} ms, COPY={} ms, "
              + "throughput={} rows/sec, {} MB/sec",
          result.rowsCopied(),
          result.fileSizeBytes() / (1024 * 1024),
          result.totalDuration().toMillis(),
          result.copyDuration().toMillis(),
          String.format("%.0f", result.throughputRowsPerSec()),
          String.format("%.1f", result.throughputMbPerSec()));

      if (!parsed.skipHosts) {
        populator.populate();
      }
    } catch (Exception e) {
      log.error("ETL failed", e);
      exit = 1;
    }
    System.exit(exit);
  }

  private static Args parseArgs(String[] args) {
    Args a = new Args();
    for (String arg : args) {
      if (arg.startsWith("--csv=")) {
        a.csvPath = Paths.get(arg.substring("--csv=".length()));
      } else if (arg.equals("--truncate-first")) {
        a.truncateFirst = true;
      } else if (arg.equals("--skip-hosts")) {
        a.skipHosts = true;
      }
    }
    return a;
  }

  private static final class Args {
    Path csvPath;
    boolean truncateFirst = false;
    boolean skipHosts = false;
  }
}
