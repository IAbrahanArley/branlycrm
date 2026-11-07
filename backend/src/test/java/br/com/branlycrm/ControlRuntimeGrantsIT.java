package br.com.branlycrm;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.RUNTIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.support.TestPostgres;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The control database as the real API role sees it after V8 (ADR-028): clinics are created only
 * through the allowed columns and always start PROVISIONING, the API cannot change a clinic's
 * status or agency nor write accounts and memberships directly, and the only activation it may
 * perform is AWAITING_OWNER to ACTIVE through activate_awaiting_clinic, still requiring an owner.
 */
@Testcontainers
class ControlRuntimeGrantsIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  static JdbcTemplate superuser;
  static JdbcTemplate runtime;
  static TransactionTemplate runtimeTransactions;

  @BeforeAll
  static void migrateWithDeploymentRoles() throws SQLException {
    TestPostgres.bootstrapRoles(POSTGRES);
    String url = TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName());
    var admin = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(admin).locations("classpath:db/migration").load().migrate();
    superuser = new JdbcTemplate(admin);
    var api = new DriverManagerDataSource(url, RUNTIME, PASSWORD);
    runtime = new JdbcTemplate(api);
    runtimeTransactions = new TransactionTemplate(new DataSourceTransactionManager(api));
  }

  @Test
  void runtimeCannotCreateClinicsYet() {
    // The column grant (never status) comes with the agency service that validates the agency.
    UUID tenant = UUID.randomUUID();
    String suffix = suffix(tenant);
    assertDenied(
        () ->
            runtime.update(
                "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
                    + " credential_reference) VALUES (?, 'Clínica sintética', ?, ?, ?)",
                tenant,
                "clinic_" + suffix,
                "tenant_" + suffix,
                "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT)));
  }

  @Test
  void updatedAtGrantOnlyAllowsLockingAndChangesNothingElse() {
    UUID agency = agency();
    UUID tenant = clinic("ACTIVE", agency);
    runtime.update(
        "UPDATE tenants SET updated_at = TIMESTAMPTZ '2000-01-01 00:00:00Z' WHERE tenant_id = ?",
        tenant);
    var row =
        superuser.queryForMap(
            "SELECT status, agency_id, updated_at > now() - interval '1 minute' AS fresh"
                + " FROM tenants WHERE tenant_id = ?",
            tenant);
    assertThat(row).containsEntry("status", "ACTIVE").containsEntry("agency_id", agency);
    assertThat(row.get("fresh")).as("V2 trigger rewrites updated_at").isEqualTo(true);
  }

  @Test
  void runtimeCannotChangeClinicStatusAgencyNorDeleteClinics() {
    UUID tenant = clinic("ACTIVE", null);
    assertDenied(
        () ->
            runtime.update("UPDATE tenants SET status = 'SUSPENDED' WHERE tenant_id = ?", tenant));
    assertDenied(
        () ->
            runtime.update(
                "UPDATE tenants SET agency_id = ? WHERE tenant_id = ?", agency(), tenant));
    assertDenied(
        () ->
            runtime.update("UPDATE tenants SET clinic_name = 'Outra' WHERE tenant_id = ?", tenant));
    assertDenied(() -> runtime.update("DELETE FROM tenants WHERE tenant_id = ?", tenant));
    assertThat(status(tenant)).isEqualTo("ACTIVE");
  }

  @Test
  void runtimeStillLocksClinicRowsForTeamChanges() {
    UUID tenant = clinic("ACTIVE", null);
    String locked =
        runtimeTransactions.execute(
            status ->
                runtime.queryForObject(
                    "SELECT status FROM tenants WHERE tenant_id = ? FOR NO KEY UPDATE",
                    String.class,
                    tenant));
    assertThat(locked).isEqualTo("ACTIVE");
  }

  @Test
  void runtimeWritesAccountsAndMembershipsOnlyThroughTheInvitationColumns() {
    // V9 (ADR-028): what the invitation acceptance needs, column by column, and nothing else.
    UUID tenant = clinic("ACTIVE", null);
    UUID subject = account();
    assertDenied(
        () ->
            runtime.update(
                "INSERT INTO user_accounts (subject_id, email, display_name, password_hash)"
                    + " VALUES (?, 'chosen-id@example.test', 'Synthetic', '{bcrypt}synthetic')",
                UUID.randomUUID()));
    assertDenied(
        () ->
            runtime.update(
                "INSERT INTO user_accounts (email, display_name, password_hash, active)"
                    + " VALUES ('inactive@example.test', 'Synthetic', '{bcrypt}synthetic', true)"));
    assertDenied(
        () ->
            runtime.update(
                "UPDATE user_accounts SET active = false WHERE subject_id = ?", subject));
    assertDenied(
        () ->
            runtime.update(
                "UPDATE user_accounts SET email = 'other@example.test' WHERE subject_id = ?",
                subject));
    assertDenied(() -> runtime.update("DELETE FROM user_accounts WHERE subject_id = ?", subject));
    assertDenied(
        () ->
            runtime.update(
                "INSERT INTO tenant_memberships (tenant_id, subject_id, role, active)"
                    + " VALUES (?, ?, 'OWNER', true)",
                tenant,
                subject));
    assertDenied(
        () ->
            runtime.update(
                "UPDATE user_accounts SET password_hash = '{bcrypt}x' WHERE subject_id = ?",
                subject));
    assertDenied(
        () ->
            runtime.update(
                "UPDATE user_accounts SET email_verified = false WHERE subject_id = ?", subject));
    assertDenied(
        () ->
            runtime.update(
                "INSERT INTO invitations (token_hash, email, kind, tenant_id, role, expires_at,"
                    + " accepted_at) VALUES (decode(repeat('00', 32), 'hex'), 'x@example.test',"
                    + " 'CLINIC', ?, 'VIEWER', now() + interval '1 day', now())",
                tenant));
    // V10: only an active, unverified account can be claimed, and only with a bcrypt hash.
    assertThat(claim(subject, "{bcrypt}new")).as("verified account").isFalse();
    UUID unverified =
        superuser.queryForObject(
            "INSERT INTO user_accounts (email, display_name, password_hash)"
                + " VALUES ('unverified@example.test', 'Synthetic', '{bcrypt}old')"
                + " RETURNING subject_id",
            UUID.class);
    assertThat(claim(unverified, "plain-text")).isFalse();
    assertThat(claim(unverified, "{bcrypt}new")).isTrue();
    assertThat(claim(unverified, "{bcrypt}again")).as("now verified").isFalse();
    assertDenied(() -> runtime.update("DELETE FROM invitations"));
    assertDenied(
        () ->
            runtime.update(
                "UPDATE invitations SET email = 'other@example.test', tenant_id = ?", tenant));

    UUID created =
        runtime.queryForObject(
            "INSERT INTO user_accounts (email, display_name, password_hash, email_verified)"
                + " VALUES ('invited@example.test', 'Synthetic', '{bcrypt}synthetic', true)"
                + " RETURNING subject_id",
            UUID.class);
    runtime.update(
        "INSERT INTO tenant_memberships (tenant_id, subject_id, role) VALUES (?, ?, 'VIEWER')",
        tenant,
        created);
    assertThat(runtime.queryForObject("SELECT count(*) FROM agencies", Integer.class)).isNotNull();
  }

  @Test
  void runtimeActivatesOnlyAwaitingClinicsAndOnlyWithAnOwner() {
    UUID awaiting = clinic("AWAITING_OWNER", agency());
    assertThatThrownBy(() -> activate(awaiting))
        .as("no owner yet")
        .hasRootCauseInstanceOf(SQLException.class)
        .rootCause()
        .extracting(cause -> ((SQLException) cause).getSQLState())
        .isEqualTo("BRL01");
    assertThat(status(awaiting)).isEqualTo("AWAITING_OWNER");

    owner(awaiting);
    assertThat(activate(awaiting)).isTrue();
    assertThat(status(awaiting)).isEqualTo("ACTIVE");
    assertThat(activate(awaiting)).as("already active").isFalse();

    for (String other : new String[] {"PROVISIONING", "SUSPENDED", "FAILED"}) {
      UUID clinic = clinic(other, null);
      owner(clinic);
      assertThat(activate(clinic)).as(other).isFalse();
      assertThat(status(clinic)).isEqualTo(other);
    }
  }

  @Test
  void lifecycleTriggerRefusesInvalidTransitionsAndAgencyChanges() {
    UUID active = clinic("ACTIVE", null);
    for (String target : new String[] {"PROVISIONING", "AWAITING_OWNER", "FAILED"}) {
      assertBrl02(
          () ->
              superuser.update(
                  "UPDATE tenants SET status = ? WHERE tenant_id = ?", target, active));
    }
    assertBrl02(
        () ->
            superuser.update(
                "UPDATE tenants SET agency_id = ? WHERE tenant_id = ?", agency(), active));
    UUID failed = clinic("FAILED", null);
    assertBrl02(
        () -> superuser.update("UPDATE tenants SET status = 'ACTIVE' WHERE tenant_id = ?", failed));
    superuser.update("UPDATE tenants SET status = 'PROVISIONING' WHERE tenant_id = ?", failed);
    assertThat(status(failed)).isEqualTo("PROVISIONING");
    UUID awaiting = clinic("AWAITING_OWNER", agency());
    for (String target : new String[] {"PROVISIONING", "FAILED"}) {
      assertBrl02(
          () ->
              superuser.update(
                  "UPDATE tenants SET status = ? WHERE tenant_id = ?", target, awaiting));
    }
    assertBrl02(
        () ->
            superuser.update(
                "UPDATE tenants SET agency_request_id = ? WHERE tenant_id = ?",
                UUID.randomUUID(),
                awaiting));
    superuser.update("UPDATE tenants SET status = 'SUSPENDED' WHERE tenant_id = ?", awaiting);
    assertThat(status(awaiting)).isEqualTo("SUSPENDED");
    // A suspension is never lifted through AWAITING_OWNER and activate_awaiting_clinic.
    assertBrl02(
        () ->
            superuser.update(
                "UPDATE tenants SET status = 'AWAITING_OWNER' WHERE tenant_id = ?", awaiting));
    owner(awaiting);
    superuser.update("UPDATE tenants SET status = 'ACTIVE' WHERE tenant_id = ?", awaiting);
    assertThat(status(awaiting)).isEqualTo("ACTIVE");
  }

  @Test
  void requestIdIsUniqueWithinAnAgencyOnly() {
    UUID agencyA = agency();
    UUID agencyB = agency();
    UUID request = UUID.randomUUID();
    clinic("PROVISIONING", agencyA, request);
    clinic("PROVISIONING", agencyB, request);
    assertThatThrownBy(() -> clinic("PROVISIONING", agencyA, request))
        .hasRootCauseInstanceOf(SQLException.class)
        .rootCause()
        .extracting(cause -> ((SQLException) cause).getSQLState())
        .isEqualTo("23505");
    assertThatThrownBy(() -> clinic("PROVISIONING", null, UUID.randomUUID()))
        .as("a request id needs an agency")
        .hasRootCauseInstanceOf(SQLException.class)
        .rootCause()
        .extracting(cause -> ((SQLException) cause).getSQLState())
        .isEqualTo("23514");
  }

  private static boolean claim(UUID subject, String hash) {
    return Boolean.TRUE.equals(
        runtime.queryForObject(
            "SELECT claim_unverified_account(?, 'Synthetic', ?)", Boolean.class, subject, hash));
  }

  private static boolean activate(UUID tenant) {
    return Boolean.TRUE.equals(
        runtime.queryForObject("SELECT activate_awaiting_clinic(?)", Boolean.class, tenant));
  }

  private static void assertDenied(ThrowingCallable call) {
    assertThatThrownBy(call)
        .hasRootCauseInstanceOf(SQLException.class)
        .rootCause()
        .extracting(cause -> ((SQLException) cause).getSQLState())
        .isEqualTo("42501");
  }

  private static void assertBrl02(ThrowingCallable call) {
    assertThatThrownBy(call)
        .hasRootCauseInstanceOf(SQLException.class)
        .rootCause()
        .extracting(cause -> ((SQLException) cause).getSQLState())
        .isEqualTo("BRL02");
  }

  private static UUID agency() {
    return superuser.queryForObject(
        "INSERT INTO agencies (name) VALUES ('Agência sintética') RETURNING agency_id", UUID.class);
  }

  private static UUID clinic(String status, UUID agency) {
    return clinic(status, agency, null);
  }

  private static UUID clinic(String status, UUID agency, UUID request) {
    UUID id = UUID.randomUUID();
    String suffix = suffix(id);
    superuser.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference, status, agency_id, agency_request_id)"
            + " VALUES (?, 'Clínica sintética', ?, ?, ?, ?, ?, ?)",
        id,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT),
        status,
        agency,
        request);
    return id;
  }

  private static UUID account() {
    UUID id = UUID.randomUUID();
    superuser.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, 'Synthetic', '{bcrypt}synthetic', true)",
        id,
        id + "@example.test");
    return id;
  }

  private static void owner(UUID tenant) {
    superuser.update(
        "INSERT INTO tenant_memberships (tenant_id, subject_id, role) VALUES (?, ?, 'OWNER')",
        tenant,
        account());
  }

  private static String status(UUID tenant) {
    return superuser.queryForObject(
        "SELECT status FROM tenants WHERE tenant_id = ?", String.class, tenant);
  }

  private static String suffix(UUID id) {
    return id.toString().replace("-", "");
  }
}
