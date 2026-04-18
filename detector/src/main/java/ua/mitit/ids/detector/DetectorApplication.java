package ua.mitit.ids.detector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Detector service Spring Boot entry.
 *
 * <p>Scan includes {@code ua.mitit.ids.common.neo4j} so the shared {@code Neo4jClientConfig} Driver
 * bean (T05) is available to baseline (T09) and detector (T10-T13) components.
 */
@SpringBootApplication(scanBasePackages = {"ua.mitit.ids.detector", "ua.mitit.ids.common.neo4j"})
public class DetectorApplication {

  public static void main(String[] args) {
    SpringApplication.run(DetectorApplication.class, args);
  }
}
