package ua.mitit.ids.evaluation.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL DataSource + JdbcTemplate beans for the evaluation module.
 *
 * <p>Reads {@code postgres.*} properties (consistent with etl module). {@code
 * initializationFailTimeout = -1} keeps context boot lazy — connection failures surface on first
 * query, not at startup.
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

  @Bean(destroyMethod = "close")
  public DataSource postgresDataSource() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(username);
    config.setPassword(password);
    config.setMaximumPoolSize(maxPoolSize);
    config.setInitializationFailTimeout(-1);
    config.setPoolName("agids-evaluation-pg");
    return new HikariDataSource(config);
  }

  @Bean
  public JdbcTemplate jdbcTemplate(DataSource ds) {
    return new JdbcTemplate(ds);
  }
}
