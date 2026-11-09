package br.com.branlycrm.platform.repositories;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes of the platform command, as crm_platform_operator: agencies (V11) and agency admin
 * invitations through platform_issue_agency_admin_invitation (V12), and the second-factor reset
 * through platform_reset_mfa (V13). The role has no direct privilege on invitations nor on the
 * second-factor tables and cannot read accounts, clinics or memberships.
 */
public class PlatformRepository {
  private final JdbcTemplate jdbc;

  public PlatformRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The agency of a request: created now or by an earlier run with the same request id. */
  public record CreatedAgency(UUID agencyId, String name) {}

  public CreatedAgency createAgency(UUID requestId, String name) {
    jdbc.update(
        "INSERT INTO agencies (name, created_request_id) VALUES (?, ?)"
            + " ON CONFLICT (created_request_id) DO NOTHING",
        name,
        requestId);
    return jdbc.queryForObject(
        "SELECT agency_id, name FROM agencies WHERE created_request_id = ?",
        (row, index) ->
            new CreatedAgency(row.getObject("agency_id", UUID.class), row.getString("name")),
        requestId);
  }

  public Optional<String> agencyStatus(UUID agencyId) {
    return jdbc
        .query(
            "SELECT status FROM agencies WHERE agency_id = ?",
            (row, index) -> row.getString("status"),
            agencyId)
        .stream()
        .findFirst();
  }

  /** Inserts or rotates the pending invitation; the function refuses a suspended agency. */
  public UUID issueAdminInvitation(
      UUID agencyId, String email, byte[] tokenHash, Instant expiresAt) {
    return jdbc.queryForObject(
        "SELECT platform_issue_agency_admin_invitation(?, ?, ?, ?)",
        UUID.class,
        agencyId,
        email,
        tokenHash,
        Timestamp.from(expiresAt));
  }

  /**
   * Removes the second factor and recovery codes of the account and revokes its sessions. True when
   * a confirmed factor existed; an unknown address is also false.
   */
  public boolean resetMfa(String email) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT platform_reset_mfa(?)", Boolean.class, email));
  }

  public Instant now() {
    return jdbc.queryForObject(
        "SELECT now() AS now", (row, index) -> row.getTimestamp("now").toInstant());
  }
}
