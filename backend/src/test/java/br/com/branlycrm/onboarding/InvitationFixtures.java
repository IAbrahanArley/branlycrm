package br.com.branlycrm.onboarding;

import br.com.branlycrm.onboarding.domain.InvitationToken;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Synthetic control-database fixtures for invitation tests, written as the container superuser
 * while the application itself runs as crm_control_runtime. Only @example.test addresses.
 */
final class InvitationFixtures {
  static final String PASSWORD = "Synthetic invitation secret 2026!";
  private static final Pattern LINK = Pattern.compile("/convite#token=([A-Za-z0-9_-]{43})");

  private final JdbcTemplate superuser;
  private final PasswordEncoder encoder;
  private final Path mail;

  InvitationFixtures(JdbcTemplate superuser, PasswordEncoder encoder, Path mail) {
    this.superuser = superuser;
    this.encoder = encoder;
    this.mail = mail;
  }

  /** Each class owns a disposable container; never clean the developer database. */
  void reset() {
    // Suspend first: the V6 trigger forbids removing the last owner of an ACTIVE clinic.
    superuser.update("UPDATE tenants SET status = 'SUSPENDED' WHERE status = 'ACTIVE'");
    superuser.update("DELETE FROM invitations");
    superuser.update("DELETE FROM tenant_provisioning_tasks");
    superuser.update("DELETE FROM tenant_memberships");
    superuser.update("DELETE FROM agency_memberships");
    superuser.update("DELETE FROM tenants");
    superuser.update("DELETE FROM agencies");
    superuser.update("DELETE FROM account_notices");
    superuser.update("DELETE FROM user_mfa_recovery_codes");
    superuser.update("DELETE FROM user_mfa_totp");
    superuser.update("DELETE FROM user_accounts");
    try (var files = Files.list(mail)) {
      for (Path file : files.toList()) Files.delete(file);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  UUID clinic(String name, String status, UUID agency) {
    UUID id = UUID.randomUUID();
    String suffix = id.toString().replace("-", "");
    superuser.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference, status, agency_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
        id,
        name,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT),
        status,
        agency);
    return id;
  }

  UUID agency(String status) {
    return superuser.queryForObject(
        "INSERT INTO agencies (name, status) VALUES ('Agência sintética', ?) RETURNING agency_id",
        UUID.class,
        status);
  }

  UUID account(String email, boolean active, boolean verified) {
    UUID id = UUID.randomUUID();
    superuser.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, active,"
            + " email_verified) VALUES (?, ?, ?, ?, ?, ?)",
        id,
        email,
        "Synthetic " + email.substring(0, email.indexOf('@')),
        encoder.encode(PASSWORD),
        active,
        verified);
    return id;
  }

  void member(UUID tenant, UUID subject, String role, boolean active) {
    superuser.update(
        "INSERT INTO tenant_memberships (tenant_id, subject_id, role, active) VALUES (?, ?, ?, ?)",
        tenant,
        subject,
        role,
        active);
  }

  void agencyAdmin(UUID agency, UUID subject) {
    superuser.update(
        "INSERT INTO agency_memberships (agency_id, subject_id, role) VALUES (?, ?, 'AGENCY_ADMIN')",
        agency,
        subject);
  }

  /** What the agency module will do (PR 3): a first-owner invitation issued by the agency. */
  String agencyOwnerInvitation(UUID tenant, String email, UUID agency, UUID invitedBy) {
    InvitationToken token = InvitationToken.generate();
    superuser.update(
        "INSERT INTO invitations (token_hash, email, kind, tenant_id, role, invited_by,"
            + " issuer_agency_id, expires_at) VALUES (?, ?, 'CLINIC', ?, 'OWNER', ?, ?, ?)",
        token.hash(),
        email,
        tenant,
        invitedBy,
        agency,
        Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS)));
    return token.raw();
  }

  /** Token of the most recent e-mail written by the local adapter. */
  String lastMailedToken() {
    try (var files = Files.list(mail)) {
      Path newest =
          files
              .max(Comparator.comparing(InvitationFixtures::modified))
              .orElseThrow(() -> new AssertionError("No e-mail was written"));
      Matcher link = LINK.matcher(Files.readString(newest));
      if (!link.find()) throw new AssertionError("E-mail without invitation link");
      return link.group(1);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  long mailCount() {
    try (var files = Files.list(mail)) {
      return files.count();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  String role(UUID tenant, UUID subject) {
    return superuser
        .query(
            "SELECT role FROM tenant_memberships WHERE tenant_id = ? AND subject_id = ? AND active",
            (row, index) -> row.getString("role"),
            tenant,
            subject)
        .stream()
        .findFirst()
        .orElse(null);
  }

  String status(UUID tenant) {
    return superuser.queryForObject(
        "SELECT status FROM tenants WHERE tenant_id = ?", String.class, tenant);
  }

  UUID subject(String email) {
    return superuser
        .query(
            "SELECT subject_id FROM user_accounts WHERE email = ?",
            (row, index) -> row.getObject("subject_id", UUID.class),
            email)
        .stream()
        .findFirst()
        .orElse(null);
  }

  JdbcTemplate jdbc() {
    return superuser;
  }

  private static Instant modified(Path file) {
    try {
      return Files.getLastModifiedTime(file).toInstant();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
