package ua.mitit.ids.etl.neo4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ua.mitit.ids.common.neo4j.MigrationRunner;

/**
 * Integration test for {@link Neo4jUnwindLoader} using a real Neo4j container.
 *
 * <p>Skips gracefully when Docker auto-discovery fails (same pattern as {@code
 * MigrationRunnerTest}, {@code FlowsCopyLoaderIT}). End-to-end behaviour is still exercised against
 * the live Compose Neo4j — see the T07 acceptance run.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(OrderAnnotation.class)
class Neo4jUnwindLoaderIT {

  private static Neo4jContainer<?> container;
  private static Driver driver;

  @BeforeAll
  static void setup() throws Exception {
    container =
        new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26-community"))
            .withAdminPassword("test-password");
    container.start();

    driver =
        GraphDatabase.driver(container.getBoltUrl(), AuthTokens.basic("neo4j", "test-password"));

    // Apply V001 + V002 via the same runner used in production (T05).
    MigrationRunner runner = new MigrationRunner(driver, "classpath:neo4j/migrations");
    runner.migrate();
  }

  @AfterAll
  static void teardown() {
    if (driver != null) {
      driver.close();
    }
    if (container != null) {
      container.stop();
    }
  }

  @Test
  @Order(1)
  void loads_edges_into_empty_graph() throws Exception {
    Path csv = Files.createTempFile("edges-test", ".csv");
    Files.writeString(
        csv,
        """
        Source IP,Destination IP,Protocol,time_bucket,t_start,t_end,src_port_first,dst_port_first,bytes_fwd,bytes_bwd,packets_fwd,packets_bwd,syn_count,rst_count,psh_count,ack_count,fin_count,flow_count,Label
        192.168.1.10,10.0.0.5,6,25000001,2017-07-04T09:00:00+00:00,2017-07-04T09:01:00+00:00,54321,80,2048,8192,10,40,2,0,10,40,5,5,BENIGN
        192.168.1.11,10.0.0.5,6,25000001,2017-07-04T09:00:00+00:00,2017-07-04T09:01:00+00:00,33333,443,1024,4096,5,20,1,0,5,20,2,3,BENIGN
        10.0.0.9,10.0.0.5,6,25000002,2017-07-04T09:01:00+00:00,2017-07-04T09:02:00+00:00,44444,22,500,1000,5,5,5,0,0,5,5,1,SSH-Patator
        """);

    Neo4jUnwindLoader loader = new Neo4jUnwindLoader(driver);
    Neo4jUnwindLoader.LoadResult result = loader.load(csv, true);

    assertThat(result.totalEdges()).isEqualTo(3);

    try (Session session = driver.session()) {
      long hostCount =
          session.run("MATCH (h:Host) RETURN COUNT(h) AS n").single().get("n").asLong();
      // 4 unique hosts: 192.168.1.10, 192.168.1.11, 10.0.0.9, 10.0.0.5
      assertThat(hostCount).isEqualTo(4);

      long edgeCount =
          session
              .run("MATCH ()-[r:CONNECTS_TO]->() RETURN COUNT(r) AS n")
              .single()
              .get("n")
              .asLong();
      assertThat(edgeCount).isEqualTo(3);

      long attackCount =
          session
              .run("MATCH ()-[r:CONNECTS_TO]->() WHERE r.label <> 'BENIGN' RETURN COUNT(r) AS n")
              .single()
              .get("n")
              .asLong();
      assertThat(attackCount).isEqualTo(1);
    }

    Files.deleteIfExists(csv);
  }

  @Test
  @Order(2)
  void wipe_first_removes_previous_data() throws Exception {
    Path csv = Files.createTempFile("edges-test2", ".csv");
    Files.writeString(
        csv,
        """
        Source IP,Destination IP,Protocol,time_bucket,t_start,t_end,src_port_first,dst_port_first,bytes_fwd,bytes_bwd,packets_fwd,packets_bwd,syn_count,rst_count,psh_count,ack_count,fin_count,flow_count,Label
        172.16.0.1,172.16.0.2,17,25000003,2017-07-04T10:00:00+00:00,2017-07-04T10:01:00+00:00,12345,53,100,200,1,1,0,0,0,0,0,1,BENIGN
        """);

    Neo4jUnwindLoader loader = new Neo4jUnwindLoader(driver);
    loader.load(csv, true);

    try (Session session = driver.session()) {
      long edgeCount =
          session
              .run("MATCH ()-[r:CONNECTS_TO]->() RETURN COUNT(r) AS n")
              .single()
              .get("n")
              .asLong();
      assertThat(edgeCount).isEqualTo(1);

      long hostCount =
          session.run("MATCH (h:Host) RETURN COUNT(h) AS n").single().get("n").asLong();
      assertThat(hostCount).isEqualTo(2);
    }

    Files.deleteIfExists(csv);
  }
}
