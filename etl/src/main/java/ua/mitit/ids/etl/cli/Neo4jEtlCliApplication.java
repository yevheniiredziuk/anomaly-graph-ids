package ua.mitit.ids.etl.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import ua.mitit.ids.etl.EtlApplication;
import ua.mitit.ids.etl.neo4j.Neo4jUnwindLoader;

/**
 * CLI entry point for Neo4j ETL.
 *
 * <p>Usage:
 *
 * <pre>
 *   (cd etl && ../mvnw spring-boot:run \
 *       -Dspring-boot.run.main-class=ua.mitit.ids.etl.cli.Neo4jEtlCliApplication \
 *       -Dspring-boot.run.arguments="--csv=../data/neo4j-import/cicids2017_mon_tue_wed.csv --wipe-first")
 * </pre>
 *
 * <p>Flags:
 *
 * <ul>
 *   <li>{@code --csv=<path>} — path to aggregated edges CSV (required)
 *   <li>{@code --wipe-first} — DETACH DELETE all nodes before loading (default: false)
 * </ul>
 *
 * <p>Same POJO pattern as {@link PostgresEtlCliApplication}: plain main that boots the {@link
 * EtlApplication} context and invokes the loader imperatively. Not annotated as
 * {@code @SpringBootApplication + CommandLineRunner} to avoid accidental execution during
 * context-load tests.
 */
public final class Neo4jEtlCliApplication {

  private static final Logger log = LoggerFactory.getLogger(Neo4jEtlCliApplication.class);

  private Neo4jEtlCliApplication() {}

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
      Neo4jUnwindLoader loader = ctx.getBean(Neo4jUnwindLoader.class);
      Neo4jUnwindLoader.LoadResult result = loader.load(parsed.csvPath, parsed.wipeFirst);

      log.info(
          "Load complete: edges={}, batches={}, total={} ms, throughput={} edges/sec",
          result.totalEdges(),
          result.totalBatches(),
          result.totalDuration().toMillis(),
          String.format("%.0f", result.throughputEdgesPerSec()));
    } catch (Exception e) {
      log.error("Neo4j ETL failed", e);
      exit = 1;
    }
    System.exit(exit);
  }

  private static Args parseArgs(String[] args) {
    Args a = new Args();
    for (String arg : args) {
      if (arg.startsWith("--csv=")) {
        a.csvPath = Paths.get(arg.substring("--csv=".length()));
      } else if (arg.equals("--wipe-first")) {
        a.wipeFirst = true;
      }
    }
    return a;
  }

  private static final class Args {
    Path csvPath;
    boolean wipeFirst = false;
  }
}
