package ua.mitit.ids.common.neo4j;

import java.util.concurrent.TimeUnit;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Logging;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared Neo4j Driver configuration for both ETL and Detector services. Reads Bolt URL /
 * credentials / pool settings from Spring environment (application.yml or env vars).
 *
 * <p>Deliberately uses raw neo4j-java-driver rather than Spring Data Neo4j's managed Driver bean:
 * our workloads include direct Cypher with UNWIND batches and GDS procedure calls that SDN does not
 * express idiomatically.
 */
@Configuration
public class Neo4jClientConfig {

  @Value("${neo4j.uri:bolt://localhost:7687}")
  private String uri;

  @Value("${neo4j.username:neo4j}")
  private String username;

  @Value("${neo4j.password:changeme-local-only}")
  private String password;

  @Value("${neo4j.pool.max-size:50}")
  private int maxPoolSize;

  @Value("${neo4j.pool.connection-acquisition-timeout-seconds:60}")
  private long connectionAcquisitionTimeoutSeconds;

  @Bean(destroyMethod = "close")
  public Driver neo4jDriver() {
    Config config =
        Config.builder()
            .withMaxConnectionPoolSize(maxPoolSize)
            .withConnectionAcquisitionTimeout(connectionAcquisitionTimeoutSeconds, TimeUnit.SECONDS)
            .withLogging(Logging.slf4j())
            .build();
    return GraphDatabase.driver(uri, AuthTokens.basic(username, password), config);
  }
}
