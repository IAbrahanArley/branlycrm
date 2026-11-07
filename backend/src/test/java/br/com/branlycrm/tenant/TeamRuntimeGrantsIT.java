package br.com.branlycrm.tenant;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.RUNTIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.tenant.domain.ClinicRole;
import br.com.branlycrm.tenant.exceptions.TeamException;
import br.com.branlycrm.tenant.services.TeamService;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Team changes executed as the real API role (crm_control_runtime) after V6 narrowed its grants:
 * row locks with a column-only UPDATE grant, triggers running as invoker, and no way to delete or
 * move memberships. Other team tests use the container superuser and cannot prove this.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.docker.compose.enabled=false")
class TeamRuntimeGrantsIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  @Autowired TeamService team;
  @Autowired JdbcTemplate runtime;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String control = POSTGRES.getDatabaseName();
    registry.add("spring.datasource.url", () -> TestPostgres.url(POSTGRES, control));
    registry.add("spring.datasource.username", () -> RUNTIME);
    registry.add("spring.datasource.password", () -> PASSWORD);
    registry.add("spring.flyway.url", () -> TestPostgres.url(POSTGRES, control));
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @BeforeAll
  static void deploymentRoles() throws SQLException {
    TestPostgres.bootstrapRoles(POSTGRES);
  }

  @Test
  void runtimeRoleManagesTheTeamButCannotBypassTheOwnerInvariant() {
    JdbcTemplate superuser = superuser();
    UUID clinic = clinic(superuser);
    UUID owner = member(superuser, clinic, "OWNER");
    UUID coOwner = member(superuser, clinic, "OWNER");
    UUID seller = member(superuser, clinic, "ATTENDANT");
    assertThat(runtimeUser()).isEqualTo(RUNTIME);

    // Locks (FOR NO KEY UPDATE, FOR SHARE/UPDATE OF m) and UPDATE(role, active) suffice.
    assertThat(team.changeRole(clinic, owner, seller, ClinicRole.VIEWER).role())
        .isEqualTo("VIEWER");
    team.deactivate(clinic, owner, seller);
    team.changeRole(clinic, owner, coOwner, ClinicRole.MANAGER);

    // The last-owner trigger runs with the runtime's privileges and still refuses.
    assertThatThrownBy(
            () ->
                runtime.update(
                    "UPDATE tenant_memberships SET role = 'MANAGER' WHERE subject_id = ?", owner))
        .hasRootCauseInstanceOf(SQLException.class)
        .rootCause()
        .extracting(cause -> ((SQLException) cause).getSQLState())
        .isEqualTo("BRL01");
    assertThatThrownBy(() -> team.deactivate(clinic, coOwner, owner))
        .as("a MANAGER cannot touch the owner")
        .isInstanceOf(TeamException.class);

    // V6 removed DELETE and UPDATE of tenant_id/subject_id from the runtime role.
    for (String forbidden :
        new String[] {
          "DELETE FROM tenant_memberships WHERE subject_id = '" + seller + "'",
          "UPDATE tenant_memberships SET tenant_id = tenant_id WHERE subject_id = '" + seller + "'",
          "UPDATE tenant_memberships SET subject_id = subject_id WHERE subject_id = '"
              + seller
              + "'"
        }) {
      assertThatThrownBy(() -> runtime.execute(forbidden))
          .as(forbidden)
          .hasRootCauseInstanceOf(SQLException.class)
          .rootCause()
          .extracting(cause -> ((SQLException) cause).getSQLState())
          .isEqualTo("42501");
    }
    assertThat(
            superuser.queryForObject(
                "SELECT count(*) FROM tenant_memberships WHERE tenant_id = ? AND role = 'OWNER'"
                    + " AND active",
                Integer.class,
                clinic))
        .isOne();
  }

  private String runtimeUser() {
    return runtime.queryForObject("SELECT current_user", String.class);
  }

  private static UUID clinic(JdbcTemplate superuser) {
    UUID id = UUID.randomUUID();
    String suffix = id.toString().replace("-", "");
    superuser.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference, status) VALUES (?, 'Clínica sintética', ?, ?, ?, 'ACTIVE')",
        id,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
    return id;
  }

  private static UUID member(JdbcTemplate superuser, UUID clinic, String role) {
    UUID id = UUID.randomUUID();
    superuser.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, 'Synthetic', '{bcrypt}synthetic', true)",
        id,
        id + "@example.test");
    superuser.update(
        "INSERT INTO tenant_memberships (tenant_id, subject_id, role) VALUES (?, ?, ?)",
        clinic,
        id,
        role);
    return id;
  }

  private static JdbcTemplate superuser() {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName()),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()));
  }
}
