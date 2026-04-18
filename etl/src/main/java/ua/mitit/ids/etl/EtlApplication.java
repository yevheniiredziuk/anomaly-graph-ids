package ua.mitit.ids.etl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ETL service Spring Boot entry.
 *
 * <p>Scan includes {@code ua.mitit.ids.common.neo4j} so the shared {@code Neo4jClientConfig} Driver
 * bean (T05) is available to the Neo4j loader (T07). The CLI wrappers ({@code
 * PostgresEtlCliApplication}, {@code Neo4jEtlCliApplication}) boot this same context.
 */
@SpringBootApplication(scanBasePackages = {"ua.mitit.ids.etl", "ua.mitit.ids.common.neo4j"})
public class EtlApplication {

  public static void main(String[] args) {
    SpringApplication.run(EtlApplication.class, args);
  }
}
