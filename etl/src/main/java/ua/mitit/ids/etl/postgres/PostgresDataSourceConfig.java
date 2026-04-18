package ua.mitit.ids.etl.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PostgreSQL DataSource for the ETL module.
 *
 * <p>HikariCP pool tuned for ETL workload: small pool (5) — ETL is single-threaded and {@code COPY}
 * locks the table — with a long connection timeout because COPY of 1M+ rows takes minutes.
 *
 * <p>{@code initializationFailTimeout = -1}: lazy connection. Context loads even if PostgreSQL is
 * unreachable at startup — useful for tests and for booting the CLI before the DB container is
 * fully ready. First {@code load()} call surfaces any real connection failure.
 */
@Configuration
public class PostgresDataSourceConfig {

  @Value("${postgres.url:jdbc:postgresql://localhost:5432/ids}")
  private String url;

  @Value("${postgres.username:ids}")
  private String username;

  @Value("${postgres.password:changeme-local-only}")
  private String password;

  @Value("${postgres.pool.max-size:5}")
  private int maxPoolSize;

  @Value("${postgres.pool.connection-timeout-ms:30000}")
  private long connectionTimeoutMs;

  @Bean(destroyMethod = "close")
  public DataSource postgresDataSource() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(username);
    config.setPassword(password);
    config.setMaximumPoolSize(maxPoolSize);
    config.setConnectionTimeout(connectionTimeoutMs);
    config.setInitializationFailTimeout(-1);
    config.setPoolName("agids-etl-pg");
    // Manual transaction boundaries per load phase (drop indexes / COPY / recreate).
    config.setAutoCommit(false);
    return new HikariDataSource(config);
  }
}
