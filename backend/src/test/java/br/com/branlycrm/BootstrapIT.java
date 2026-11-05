package br.com.branlycrm;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.TestPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Application starts against a clean PostgreSQL and exposes only health anonymously. */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.docker.compose.enabled=false")
class BootstrapIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @Test
  void controlMigrationsApplyInOrderOnAnEmptyDatabase() {
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class))
        .containsExactly("1", "2", "3", "4", "5", "6");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class))
        .isOne();
  }

  @Test
  void onlyHealthAndCsrfArePublic() throws Exception {
    Browser anonymous = new Browser(port);
    var health = anonymous.get("/actuator/health");
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.body()).contains("UP").doesNotContain("components");
    assertThat(anonymous.csrf().statusCode()).isEqualTo(200);
    assertThat(anonymous.get("/v1/auth/me").statusCode()).isEqualTo(401);
    assertThat(anonymous.get("/v1/auth/clinics").statusCode()).isEqualTo(401);
    assertThat(anonymous.get("/v3/api-docs").statusCode()).isEqualTo(401);
    assertThat(anonymous.get("/actuator/env").statusCode()).isEqualTo(401);
  }
}
