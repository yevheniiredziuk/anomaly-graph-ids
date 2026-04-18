package ua.mitit.ids.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ua.mitit.ids.evaluation.groundtruth.GroundTruthBuilder;
import ua.mitit.ids.evaluation.metrics.ConfusionMatrix;

@Testcontainers(disabledWithoutDocker = true)
class EvaluationServiceIT {

  private static PostgreSQLContainer<?> container;
  private static JdbcTemplate jdbc;
  private static GroundTruthBuilder gtBuilder;
  private static EvaluationService evaluator;

  @BeforeAll
  static void setup() {
    container =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6-alpine"))
            .withDatabaseName("ids_test")
            .withUsername("ids_test")
            .withPassword("test-pwd");
    container.start();

    DataSource ds =
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword());
    jdbc = new JdbcTemplate(ds);
    gtBuilder = new GroundTruthBuilder(jdbc);
    evaluator = new EvaluationService(jdbc);

    jdbc.execute(
        """
        CREATE TABLE flows (
            flow_id BIGSERIAL PRIMARY KEY,
            source_ip INET NOT NULL,
            destination_ip INET NOT NULL,
            t_start TIMESTAMPTZ NOT NULL,
            label TEXT NOT NULL DEFAULT 'BENIGN'
        )
        """);
    jdbc.update(
        """
        INSERT INTO flows (source_ip, destination_ip, t_start, label) VALUES
        ('10.0.0.1'::INET, '10.0.0.2'::INET, '2017-07-04 10:00:00+00', 'BENIGN'),
        ('10.0.0.5'::INET, '10.0.0.2'::INET, '2017-07-04 10:00:00+00', 'ATTACK')
        """);
  }

  @AfterAll
  static void teardown() {
    if (container != null) container.stop();
  }

  @Test
  void ground_truth_contains_both_endpoints_of_attack_flow() {
    var gt =
        gtBuilder.build(
            OffsetDateTime.parse("2017-07-04T10:00:00Z"),
            OffsetDateTime.parse("2017-07-04T10:05:00Z"),
            5);
    assertThat(gt.anomalous()).hasSize(2);
    assertThat(gt.byAttackType().get("ATTACK")).hasSize(2);
  }

  @Test
  void confusion_matrix_perfect_and_zero() {
    OffsetDateTime a = OffsetDateTime.parse("2017-07-04T10:00:00Z");
    OffsetDateTime b = OffsetDateTime.parse("2017-07-04T10:05:00Z");
    var gt = gtBuilder.build(a, b, 5);
    var universe = evaluator.buildEvaluablePairs(a, b, 5);

    ConfusionMatrix perfect = ConfusionMatrix.from(gt.anomalous(), gt.anomalous(), universe);
    assertThat(perfect.precision()).isEqualTo(1.0);
    assertThat(perfect.recall()).isEqualTo(1.0);
    assertThat(perfect.f1()).isEqualTo(1.0);

    ConfusionMatrix zero = ConfusionMatrix.from(Set.of(), gt.anomalous(), universe);
    assertThat(zero.precision()).isEqualTo(0.0);
    assertThat(zero.recall()).isEqualTo(0.0);
    assertThat(zero.f1()).isEqualTo(0.0);
  }
}
