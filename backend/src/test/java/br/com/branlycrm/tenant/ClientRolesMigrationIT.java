package br.com.branlycrm.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.support.TestPostgres;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V7 (ADR-025) applied over data written with the V6 catalog: RECEPTION and SALES become ATTENDANT,
 * PROFESSIONAL becomes VIEWER, owners and managers are untouched, and the old names are refused
 * afterwards. Runs Flyway directly so the database can stop at V6 first.
 */
@Testcontainers
class ClientRolesMigrationIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  @Test
  void convertsTheFiveRoleCatalogAndKeepsEveryOwner() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName()),
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    flyway(dataSource, "6").migrate();
    UUID clinic = clinic(jdbc);
    Map<String, UUID> before =
        Map.of(
            "OWNER", member(jdbc, clinic, "OWNER", true),
            "MANAGER", member(jdbc, clinic, "MANAGER", true),
            "RECEPTION", member(jdbc, clinic, "RECEPTION", true),
            "SALES", member(jdbc, clinic, "SALES", false),
            "PROFESSIONAL", member(jdbc, clinic, "PROFESSIONAL", true));

    flyway(dataSource, "latest").migrate();

    assertThat(role(jdbc, before.get("OWNER"))).isEqualTo("OWNER");
    assertThat(role(jdbc, before.get("MANAGER"))).isEqualTo("MANAGER");
    assertThat(role(jdbc, before.get("RECEPTION"))).isEqualTo("ATTENDANT");
    assertThat(role(jdbc, before.get("SALES"))).isEqualTo("ATTENDANT");
    assertThat(role(jdbc, before.get("PROFESSIONAL"))).isEqualTo("VIEWER");
    // Conversion never changes the active flag nor removes memberships.
    assertThat(active(jdbc, before.get("SALES"))).isFalse();
    assertThat(active(jdbc, before.get("RECEPTION"))).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM tenant_memberships WHERE tenant_id = ?",
                Integer.class,
                clinic))
        .isEqualTo(5);

    for (String retired : new String[] {"ADMIN", "RECEPTION", "SALES", "PROFESSIONAL"}) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE tenant_memberships SET role = ? WHERE subject_id = ?",
                      retired,
                      before.get("RECEPTION")))
          .as(retired)
          .isInstanceOf(DataIntegrityViolationException.class);
    }
    // The last-owner trigger from V6 still guards the new catalog.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE tenant_memberships SET role = 'VIEWER' WHERE subject_id = ?",
                    before.get("OWNER")))
        .hasRootCauseInstanceOf(SQLException.class)
        .rootCause()
        .extracting(cause -> ((SQLException) cause).getSQLState())
        .isEqualTo("BRL01");
  }

  private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target(target)
        .load();
  }

  private static UUID clinic(JdbcTemplate jdbc) {
    UUID id = UUID.randomUUID();
    String suffix = id.toString().replace("-", "");
    jdbc.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference, status) VALUES (?, 'Clínica sintética', ?, ?, ?, 'ACTIVE')",
        id,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
    return id;
  }

  private static UUID member(JdbcTemplate jdbc, UUID clinic, String role, boolean active) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, 'Synthetic', '{bcrypt}synthetic', true)",
        id,
        id + "@example.test");
    jdbc.update(
        "INSERT INTO tenant_memberships (tenant_id, subject_id, role, active) VALUES (?, ?, ?, ?)",
        clinic,
        id,
        role,
        active);
    return id;
  }

  private static String role(JdbcTemplate jdbc, UUID subject) {
    return jdbc.queryForObject(
        "SELECT role FROM tenant_memberships WHERE subject_id = ?", String.class, subject);
  }

  private static boolean active(JdbcTemplate jdbc, UUID subject) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT active FROM tenant_memberships WHERE subject_id = ?", Boolean.class, subject));
  }
}
