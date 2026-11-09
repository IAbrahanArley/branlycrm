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
  void runtimeCreatesClinicsOnlyThroughTheAllowedColumnsAndTheyStartProvisioning() {
    // V11: the agency portfolio creates clinics, never choosing their status.
    UUID agency = agency();
    UUID tenant = UUID.randomUUID();
    String suffix = suffix(tenant);
    runtime.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference, agency_id, agency_request_id)"
            + " VALUES (?, 'Clínica sintética', ?, ?, ?, ?, ?)",
        tenant,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT),
        agency,
        UUID.randomUUID());
    assertThat(status(tenant)).isEqualTo("PROVISIONING");
    runtime.update("INSERT INTO tenant_provisioning_tasks (tenant_id) VALUES (?)", tenant);
    assertThat(
            superuser.queryForObject(
                "SELECT admin_subject_id IS NULL FROM tenant_provisioning_tasks"
                    + " WHERE tenant_id = ?",
                Boolean.class,
                tenant))
        .isTrue();

    UUID other = UUID.randomUUID();
    String otherSuffix = suffix(other);
    assertDenied(
        () ->
            runtime.update(
                "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
                    + " credential_reference, status) VALUES (?, 'Clínica', ?, ?, ?, 'ACTIVE')",
                other,
                "clinic_" + otherSuffix,
                "tenant_" + otherSuffix,
                "TENANT_SECRET_" + otherSuffix.toUpperCase(Locale.ROOT)));
    assertDenied(
        () ->
            runtime.update(
                "INSERT INTO tenant_provisioning_tasks (tenant_id, admin_subject_id)"
                    + " VALUES (?, ?)",
                other,
                UUID.randomUUID()));
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
  void runtimeNeverIssuesAgencyAdminInvitations() {
    UUID agency = agency();
    assertBrl02(
        () ->
            runtime.update(
                "INSERT INTO invitations (token_hash, email, kind, agency_id, role, expires_at)"
                    + " VALUES (decode(repeat('44', 32), 'hex'), 'x@example.test', 'AGENCY', ?,"
                    + " 'AGENCY_ADMIN', now() + interval '1 day')",
                agency));
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

  /**
   * V13 (CRM-016): the runtime reads the factor columns it needs, never the recovery code hashes
   * nor the counters, and writes only through the mfa_* functions. Nobody but the platform operator
   * may reset a factor, and PUBLIC executes none of them.
   */
  @Test
  void runtimeChangesSecondFactorDataOnlyThroughTheFunctions() {
    UUID subject = account();
    byte[] ciphertext = new byte[36];
    byte[] nonce = new byte[12];
    for (String forbidden :
        new String[] {
          "INSERT INTO user_mfa_totp (subject_id) VALUES (?)",
          "UPDATE user_mfa_totp SET last_used_step = NULL WHERE subject_id = ?",
          "UPDATE user_mfa_totp SET failed_attempts = 0, locked_until = NULL WHERE subject_id = ?",
          "DELETE FROM user_mfa_totp WHERE subject_id = ?",
          "INSERT INTO user_mfa_recovery_codes (subject_id, code_hash)"
              + " VALUES (?, decode(repeat('00', 32), 'hex'))",
          "UPDATE user_mfa_recovery_codes SET used_at = NULL WHERE subject_id = ?",
          "DELETE FROM user_mfa_recovery_codes WHERE subject_id = ?",
          "SELECT code_hash FROM user_mfa_recovery_codes WHERE subject_id = ?",
          "SELECT failed_attempts FROM user_mfa_totp WHERE subject_id = ?",
          "SELECT last_used_step FROM user_mfa_totp WHERE subject_id = ?",
          "SELECT platform_reset_mfa(?::text)",
          "SELECT mfa_register_failure(?, 10, interval '15 minutes')",
          "SELECT mfa_start_lock(?)",
          "SELECT failed_attempts FROM user_mfa_totp WHERE subject_id = ?",
          "SELECT consecutive_locks FROM user_mfa_totp WHERE subject_id = ?",
          "INSERT INTO account_notices (subject_id, kind) VALUES (?, 'MFA_RESET')",
          "UPDATE account_notices SET sent_at = now() WHERE subject_id = ?",
          "DELETE FROM account_notices WHERE subject_id = ?"
        }) {
      assertDenied(() -> runtime.queryForList(forbidden, subject));
    }

    runtime.queryForObject(
        "SELECT mfa_stage_secret(?, 1, ?, ?)::text", String.class, subject, ciphertext, nonce);
    assertThat(
            runtime.queryForObject(
                "SELECT pending_nonce FROM user_mfa_totp WHERE subject_id = ?",
                byte[].class,
                subject))
        .isEqualTo(nonce);
    assertThat(
            runtime.queryForObject(
                "SELECT count(*) FROM user_mfa_recovery_codes WHERE subject_id = ?"
                    + " AND used_at IS NULL",
                Long.class,
                subject))
        .isZero();
    // The table refuses malformed secrets whatever the caller.
    assertThatThrownBy(
            () ->
                runtime.queryForObject(
                    "SELECT mfa_stage_secret(?, 1, ?, ?)::text",
                    String.class,
                    subject,
                    new byte[10],
                    nonce))
        .hasRootCauseInstanceOf(SQLException.class)
        .rootCause()
        .extracting(cause -> ((SQLException) cause).getSQLState())
        .isEqualTo("23514");
    // V14: the attempt is reserved before the comparison and the runtime cannot skip the count.
    var reserved =
        runtime.queryForMap("SELECT allowed, lock_started FROM mfa_reserve_attempt(?)", subject);
    assertThat(reserved).containsEntry("allowed", true).containsEntry("lock_started", false);
    assertThat(
            superuser.queryForObject(
                "SELECT failed_attempts FROM user_mfa_totp WHERE subject_id = ?",
                Integer.class,
                subject))
        .isOne();
    assertThat(
            runtime.queryForObject(
                "SELECT count(*) FROM account_notices WHERE subject_id = ? AND sent_at IS NULL",
                Long.class,
                subject))
        .isZero();

    // Runtime functions, the platform one, and V13's mfa_register_failure, replaced in V14.
    java.util.Map<String, String> owners = new java.util.LinkedHashMap<>();
    for (String function :
        new String[] {
          "mfa_stage_secret(uuid,integer,bytea,bytea)",
          "mfa_confirm_secret(uuid,bytea,bigint,bytea[])",
          "mfa_consume_step(uuid,bytea,bigint)",
          "mfa_consume_recovery(uuid,bytea)",
          "mfa_reserve_attempt(uuid)",
          "mfa_finish_failure(uuid)",
          "mfa_reset_failures(uuid)",
          "mfa_replace_recovery_codes(uuid,bytea[])",
          "mfa_disable(uuid)",
          "account_notice_claim(uuid,uuid)",
          "account_notice_release(uuid,uuid)"
        }) {
      owners.put(function, "runtime");
    }
    owners.put("platform_reset_mfa(varchar)", "operator");
    owners.put("mfa_register_failure(uuid,integer,interval)", "nobody");
    owners.put("mfa_start_lock(uuid)", "nobody");
    owners.forEach(
        (function, owner) -> {
          assertThat(privilege("public", function)).as("PUBLIC " + function).isFalse();
          assertThat(privilege(RUNTIME, function))
              .as("runtime " + function)
              .isEqualTo(owner.equals("runtime"));
          assertThat(privilege(TestPostgres.PLATFORM, function))
              .as("operator " + function)
              .isEqualTo(owner.equals("operator"));
        });
    for (String table :
        new String[] {"user_mfa_totp", "user_mfa_recovery_codes", "account_notices"}) {
      assertThat(
              superuser.queryForObject(
                  "SELECT has_any_column_privilege(?, ?, 'SELECT')"
                      + " OR has_table_privilege(?, ?, 'INSERT, UPDATE, DELETE')",
                  Boolean.class,
                  TestPostgres.PLATFORM,
                  table,
                  TestPostgres.PLATFORM,
                  table))
          .as("operator on " + table)
          .isFalse();
    }
  }

  private static boolean privilege(String role, String function) {
    return Boolean.TRUE.equals(
        superuser.queryForObject(
            "SELECT has_function_privilege(?, ?, 'EXECUTE')", Boolean.class, role, function));
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
