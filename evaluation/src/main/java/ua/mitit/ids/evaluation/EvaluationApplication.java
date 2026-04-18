package ua.mitit.ids.evaluation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry for the evaluation module.
 *
 * <p>Scan includes {@code ua.mitit.ids.common.neo4j} so the shared Neo4j Driver bean (T05) is
 * wired. We define our own PostgreSQL DataSource in {@code
 * ua.mitit.ids.evaluation.config.PostgresDataSourceConfig}.
 */
@SpringBootApplication(scanBasePackages = {"ua.mitit.ids.evaluation", "ua.mitit.ids.common.neo4j"})
public class EvaluationApplication {

  public static void main(String[] args) {
    SpringApplication.run(EvaluationApplication.class, args);
  }
}
