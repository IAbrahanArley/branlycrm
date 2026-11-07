package br.com.branlycrm.agency.repositories;

import br.com.branlycrm.agency.domain.Agency;
import br.com.branlycrm.agency.domain.PortfolioClinic;
import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Agencies, their admins and their portfolio, all in the control database. Nothing here opens a
 * clinic database: the portfolio is registry data only (ADR-026).
 */
@Repository
public class AgencyRepository {
  private static final RowMapper<Agency> AGENCY =
      (row, index) ->
          new Agency(
              row.getObject("agency_id", UUID.class),
              row.getString("name"),
              row.getString("status"));

  private final JdbcTemplate jdbc;

  public AgencyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Active agencies where the subject is an active admin with an active, verified account. */
  public List<Agency> listForAdmin(UUID subjectId) {
    return jdbc.query(
        """
        SELECT a.agency_id, a.name, a.status FROM agencies a
        JOIN agency_memberships m ON m.agency_id = a.agency_id
        JOIN user_accounts u ON u.subject_id = m.subject_id
        WHERE m.subject_id = ? AND m.active AND a.status = 'ACTIVE'
          AND u.active AND u.email_verified
        ORDER BY a.name, a.agency_id
        """,
        AGENCY,
        subjectId);
  }

  /** Same conditions as {@link #listForAdmin}, for one agency. */
  public boolean isActiveAdmin(UUID agencyId, UUID subjectId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM agencies a
              JOIN agency_memberships m ON m.agency_id = a.agency_id
              JOIN user_accounts u ON u.subject_id = m.subject_id
              WHERE a.agency_id = ? AND m.subject_id = ? AND m.active AND a.status = 'ACTIVE'
                AND u.active AND u.email_verified)
            """,
            Boolean.class,
            agencyId,
            subjectId));
  }

  public Optional<String> status(UUID agencyId) {
    return jdbc
        .query(
            "SELECT status FROM agencies WHERE agency_id = ?",
            (row, index) -> row.getString("status"),
            agencyId)
        .stream()
        .findFirst();
  }

  /**
   * Clinics of the portfolio with the state of their first-owner invitation. ACCEPTED once any
   * agency invitation was accepted; PENDING or EXPIRED while one is still open; NONE otherwise.
   */
  public PageResult<PortfolioClinic> portfolio(UUID agencyId, PageQuery page) {
    Long total =
        jdbc.queryForObject(
            "SELECT count(*) FROM tenants WHERE agency_id = ?", Long.class, agencyId);
    List<PortfolioClinic> items =
        jdbc.query(
            """
            SELECT t.tenant_id, t.clinic_name, t.status, t.created_at,
              CASE
                WHEN EXISTS (SELECT 1 FROM invitations i WHERE i.tenant_id = t.tenant_id
                             AND i.issuer_agency_id = t.agency_id AND i.accepted_at IS NOT NULL)
                  THEN 'ACCEPTED'
                WHEN EXISTS (SELECT 1 FROM invitations i WHERE i.tenant_id = t.tenant_id
                             AND i.issuer_agency_id = t.agency_id AND i.accepted_at IS NULL
                             AND i.revoked_at IS NULL AND i.expires_at > now())
                  THEN 'PENDING'
                WHEN EXISTS (SELECT 1 FROM invitations i WHERE i.tenant_id = t.tenant_id
                             AND i.issuer_agency_id = t.agency_id AND i.accepted_at IS NULL
                             AND i.revoked_at IS NULL)
                  THEN 'EXPIRED'
                ELSE 'NONE'
              END AS owner_invitation
            FROM tenants t
            WHERE t.agency_id = ?
            ORDER BY t.clinic_name, t.tenant_id
            LIMIT ? OFFSET ?
            """,
            (row, index) ->
                new PortfolioClinic(
                    row.getObject("tenant_id", UUID.class),
                    row.getString("clinic_name"),
                    row.getString("status"),
                    row.getString("owner_invitation"),
                    instant(row.getTimestamp("created_at"))),
            agencyId,
            page.pageSize(),
            page.offset());
    return new PageResult<>(items, total == null ? 0 : total);
  }

  /**
   * Clinics still being set up, for the per-agency cap. A transaction-level advisory lock per
   * agency serializes concurrent creations, so the cap cannot be overrun (needs no grant).
   */
  public int unfinishedClinicsLocked(UUID agencyId) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        (row, index) -> null,
        "agency-clinics:" + agencyId);
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM tenants WHERE agency_id = ?"
                + " AND status IN ('PROVISIONING', 'AWAITING_OWNER', 'FAILED')",
            Integer.class,
            agencyId);
    return count == null ? 0 : count;
  }

  /** The admin membership of a subject, active or not, locked for an acceptance. */
  public Optional<Boolean> lockMembershipActive(UUID agencyId, UUID subjectId) {
    return jdbc
        .query(
            "SELECT active FROM agency_memberships WHERE agency_id = ? AND subject_id = ?"
                + " FOR UPDATE",
            (row, index) -> row.getBoolean("active"),
            agencyId,
            subjectId)
        .stream()
        .findFirst();
  }

  public void insertAdmin(UUID agencyId, UUID subjectId) {
    jdbc.update(
        "INSERT INTO agency_memberships (agency_id, subject_id, role)"
            + " VALUES (?, ?, 'AGENCY_ADMIN')",
        agencyId,
        subjectId);
  }

  public void reactivateAdmin(UUID agencyId, UUID subjectId) {
    jdbc.update(
        "UPDATE agency_memberships SET active = true WHERE agency_id = ? AND subject_id = ?"
            + " AND NOT active",
        agencyId,
        subjectId);
  }

  private static java.time.Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}
