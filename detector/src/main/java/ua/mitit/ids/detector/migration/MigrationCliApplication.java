package ua.mitit.ids.detector.migration;

import org.neo4j.driver.Driver;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import ua.mitit.ids.common.neo4j.MigrationRunner;

/**
 * Dedicated Spring Boot application for running Neo4j schema migrations.
 *
 * <p>Usage:
 *
 * <pre>
 *   ./mvnw -pl detector -am spring-boot:run \
 *       -Dspring-boot.run.main-class=ua.mitit.ids.detector.migration.MigrationCliApplication
 * </pre>
 *
 * <p>Separate from {@code DetectorApplication} so migrations are explicitly triggered, not auto-run
 * on service startup (which would risk applying untested migrations in production).
 */
@SpringBootApplication
@ComponentScan(basePackages = {"ua.mitit.ids.common.neo4j", "ua.mitit.ids.detector.migration"})
public class MigrationCliApplication implements CommandLineRunner {

  private final Driver driver;

  public MigrationCliApplication(Driver driver) {
    this.driver = driver;
  }

  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(MigrationCliApplication.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    System.exit(SpringApplication.exit(app.run(args)));
  }

  @Override
  public void run(String... args) throws Exception {
    MigrationRunner runner = new MigrationRunner(driver, "classpath:neo4j/migrations");
    runner.migrate();
  }
}
