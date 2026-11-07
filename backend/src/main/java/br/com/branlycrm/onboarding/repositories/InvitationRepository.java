package br.com.branlycrm.onboarding.repositories;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.onboarding.domain.Invitation;
import br.com.branlycrm.onboarding.domain.InvitationKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Invitations in the control database (V9). Pending means neither accepted nor revoked; expiry is
 * checked by the service against the database clock it reads here, never by the client.
 */
@Repository
public class InvitationRepository {
  private static final String COLUMNS =
      """
      SELECT invitation_id, kind, tenant_id, agency_id, email, role, invited_by, issuer_agency_id,
             expires_at, accepted_at, revoked_at
      FROM invitations
      """;

  private static final String PENDING = " accepted_at IS NULL AND revoked_at IS NULL";

  private static final RowMapper<Invitation> INVITATION =
      (row, index) ->
          new Invitation(
              row.getObject("invitation_id", UUID.class),
              InvitationKind.valueOf(row.getString("kind")),
              row.getObject("tenant_id", UUID.class),
              row.getObject("agency_id", UUID.class),
              row.getString("email"),
              row.getString("role"),
              row.getObject("invited_by", UUID.class),
              row.getObject("issuer_agency_id", UUID.class),
              instant(row, "expires_at"),
              instant(row, "accepted_at"),
              instant(row, "revoked_at"));

  private final JdbcTemplate jdbc;

  public InvitationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The database clock, so expiry never depends on the API host's clock. */
  public Instant now() {
    return jdbc.queryForObject("SELECT now()", (row, index) -> instant(row, "now"));
  }

  /** Current time, not the transaction start: an acceptance may wait for the clinic lock. */
  public Instant clock() {
    return jdbc.queryForObject(
        "SELECT clock_timestamp() AS now", (row, index) -> instant(row, "now"));
  }

  /** Whether the subject is an active admin of the agency (an agency never self-invites). */
  public boolean isAgencyMember(UUID agencyId, UUID subjectId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM agency_memberships WHERE agency_id = ? AND subject_id = ?"
                + " AND active)",
            Boolean.class,
            agencyId,
            subjectId));
  }

  /** E-mails sent for a clinic's invitations in the last 24 hours, for the sending cap. */
  public int sentInLastDay(UUID tenantId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM invitations WHERE tenant_id = ?"
                + " AND last_sent_at > now() - interval '1 day'",
            Integer.class,
            tenantId);
    return count == null ? 0 : count;
  }

  /** How many e-mails an invitation had and how long ago the last one was sent. */
  public SendHistory sendHistory(UUID invitationId) {
    return jdbc.queryForObject(
        "SELECT send_count, COALESCE(EXTRACT(EPOCH FROM now() - last_sent_at), 1e9) AS since"
            + " FROM invitations WHERE invitation_id = ?",
        (row, index) -> new SendHistory(row.getInt("send_count"), row.getDouble("since")),
        invitationId);
  }

  public record SendHistory(int sendCount, double secondsSinceLast) {}

  public Optional<Invitation> findByTokenHash(byte[] tokenHash) {
    return jdbc.query(COLUMNS + " WHERE token_hash = ?", INVITATION, (Object) tokenHash).stream()
        .findFirst();
  }

  /**
   * Re-reads the invitation under a row lock, matching the token too: a rotation committed after
   * the first read makes the old token fail here, so a rotated link never wins a race.
   */
  public Optional<Invitation> lockWithToken(UUID invitationId, byte[] tokenHash) {
    return jdbc
        .query(
            COLUMNS + " WHERE invitation_id = ? AND token_hash = ? FOR UPDATE",
            INVITATION,
            invitationId,
            tokenHash)
        .stream()
        .findFirst();
  }

  /**
   * E-mails sent for the owner invitations an agency issued for one clinic in the last 24 hours,
   * whatever the address, and seconds since the last one (security review, M1).
   */
  public SendHistory agencyOwnerSends(UUID tenantId) {
    return jdbc.queryForObject(
        """
        SELECT COALESCE(sum(send_count) FILTER (WHERE last_sent_at > now() - interval '1 day'), 0)
                 AS sends,
               COALESCE(EXTRACT(EPOCH FROM now() - max(last_sent_at)), 1e9) AS since
        FROM invitations WHERE tenant_id = ? AND issuer_agency_id IS NOT NULL
        """,
        (row, index) -> new SendHistory(row.getInt("sends"), row.getDouble("since")),
        tenantId);
  }

  /** Owner invitation e-mails an agency sent across its portfolio in the last 24 hours. */
  public int agencySendsInLastDay(UUID agencyId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COALESCE(sum(send_count), 0) FROM invitations WHERE issuer_agency_id = ?"
                + " AND last_sent_at > now() - interval '1 day'",
            Integer.class,
            agencyId);
    return count == null ? 0 : count;
  }

  /** Re-reads the invitation under a row lock, after the clinic lock (ADR-028 lock order). */
  public Optional<Invitation> lock(UUID invitationId) {
    return jdbc
        .query(COLUMNS + " WHERE invitation_id = ? FOR UPDATE", INVITATION, invitationId)
        .stream()
        .findFirst();
  }

  public Optional<Invitation> lockPendingForClinic(UUID tenantId, UUID invitationId) {
    return jdbc
        .query(
            COLUMNS
                + " WHERE invitation_id = ? AND tenant_id = ? AND kind = 'CLINIC' AND"
                + PENDING
                + " FOR UPDATE",
            INVITATION,
            invitationId,
            tenantId)
        .stream()
        .findFirst();
  }

  /** The pending first-owner invitation an agency issued for a clinic (at most one, V9). */
  public Optional<Invitation> lockPendingAgencyOwner(UUID tenantId) {
    return jdbc
        .query(
            COLUMNS
                + " WHERE tenant_id = ? AND issuer_agency_id IS NOT NULL AND"
                + PENDING
                + " FOR UPDATE",
            INVITATION,
            tenantId)
        .stream()
        .findFirst();
  }

  public Optional<Invitation> lockPendingForClinicEmail(UUID tenantId, String email) {
    return jdbc
        .query(
            COLUMNS
                + " WHERE tenant_id = ? AND email = ? AND kind = 'CLINIC' AND"
                + PENDING
                + " FOR UPDATE",
            INVITATION,
            tenantId,
            email)
        .stream()
        .findFirst();
  }

  public UUID insertClinicInvitation(
      byte[] tokenHash,
      String email,
      UUID tenantId,
      String role,
      UUID invitedBy,
      UUID issuerAgencyId,
      Instant expiresAt) {
    return jdbc.queryForObject(
        """
        INSERT INTO invitations (token_hash, email, kind, tenant_id, role, invited_by,
                                 issuer_agency_id, expires_at, send_count, last_sent_at)
        VALUES (?, ?, 'CLINIC', ?, ?, ?, ?, ?, 1, now())
        RETURNING invitation_id
        """,
        UUID.class,
        tokenHash,
        email,
        tenantId,
        role,
        invitedBy,
        issuerAgencyId,
        Timestamp.from(expiresAt));
  }

  /** Reissues a pending invitation: new token (the old link stops working), role and expiry. */
  public void rotate(
      UUID invitationId, byte[] tokenHash, String role, UUID invitedBy, Instant expiresAt) {
    jdbc.update(
        """
        UPDATE invitations
        SET token_hash = ?, role = ?, invited_by = ?, expires_at = ?,
            send_count = send_count + 1, last_sent_at = now()
        WHERE invitation_id = ? AND accepted_at IS NULL AND revoked_at IS NULL
        """,
        tokenHash,
        role,
        invitedBy,
        Timestamp.from(expiresAt),
        invitationId);
  }

  public void markAccepted(UUID invitationId, UUID subjectId) {
    jdbc.update(
        "UPDATE invitations SET accepted_at = now(), accepted_by = ? WHERE invitation_id = ? AND"
            + PENDING,
        subjectId,
        invitationId);
  }

  public void revoke(UUID invitationId, UUID revokedBy) {
    jdbc.update(
        "UPDATE invitations SET revoked_at = now(), revoked_by = ? WHERE invitation_id = ? AND"
            + PENDING,
        revokedBy,
        invitationId);
  }

  /** Pending clinic invitations, expired ones included so they can be resent or revoked. */
  public PageResult<Invitation> listPendingForClinic(UUID tenantId, PageQuery page) {
    String where = " WHERE tenant_id = ? AND kind = 'CLINIC' AND" + PENDING;
    Long total =
        jdbc.queryForObject("SELECT count(*) FROM invitations" + where, Long.class, tenantId);
    List<Invitation> items =
        jdbc.query(
            COLUMNS + where + " ORDER BY created_at, invitation_id LIMIT ? OFFSET ?",
            INVITATION,
            tenantId,
            page.pageSize(),
            page.offset());
    return new PageResult<>(items, total == null ? 0 : total);
  }

  public Optional<String> clinicName(UUID tenantId) {
    return jdbc
        .query(
            "SELECT clinic_name FROM tenants WHERE tenant_id = ?",
            (row, index) -> row.getString("clinic_name"),
            tenantId)
        .stream()
        .findFirst();
  }

  /** Clinic or agency name shown to whoever holds the token. */
  public Optional<String> targetName(Invitation invitation) {
    String sql =
        invitation.kind() == InvitationKind.CLINIC
            ? "SELECT clinic_name AS name FROM tenants WHERE tenant_id = ?"
            : "SELECT name FROM agencies WHERE agency_id = ?";
    UUID target =
        invitation.kind() == InvitationKind.CLINIC ? invitation.tenantId() : invitation.agencyId();
    return jdbc.query(sql, (row, index) -> row.getString("name"), target).stream().findFirst();
  }

  /**
   * Whether an agency-issued owner invitation is still backed by its issuer: the agency is ACTIVE,
   * the clinic still belongs to it and, when a person issued it, that person is still an active
   * admin of the agency with an active, verified account.
   */
  public boolean agencyStillIssues(UUID agencyId, UUID tenantId, UUID invitedBy) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM agencies a JOIN tenants t ON t.agency_id = a.agency_id
              WHERE a.agency_id = ? AND t.tenant_id = ? AND a.status = 'ACTIVE'
                AND (CAST(? AS uuid) IS NULL OR EXISTS (
                  SELECT 1 FROM agency_memberships m
                  JOIN user_accounts u ON u.subject_id = m.subject_id
                  WHERE m.agency_id = a.agency_id AND m.subject_id = CAST(? AS uuid)
                    AND m.active AND u.active AND u.email_verified)))
            """,
            Boolean.class,
            agencyId,
            tenantId,
            invitedBy,
            invitedBy));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    Timestamp value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
