package br.com.branlycrm.tenant.repositories;

import br.com.branlycrm.tenant.domain.ClinicRole;
import br.com.branlycrm.tenant.domain.TeamMember;
import br.com.branlycrm.tenant.exceptions.TeamException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Memberships of one clinic in the control database. Mutations run inside the service transaction
 * after {@link #lockActiveClinic}, which serializes team changes per clinic.
 */
@Repository
public class TeamRepository {
  /** Raised by the V6 triggers protecting the last clinic owner. */
  static final String LAST_OWNER_STATE = "BRL01";

  private static final String COLUMNS =
      """
      SELECT m.subject_id, u.display_name, u.email, m.role, m.active
      FROM tenant_memberships m JOIN user_accounts u ON u.subject_id = m.subject_id
      """;

  private static final RowMapper<TeamMember> MEMBER =
      (row, index) ->
          new TeamMember(
              row.getObject("subject_id", UUID.class),
              row.getString("display_name"),
              row.getString("email"),
              ClinicRole.valueOf(row.getString("role")),
              row.getBoolean("active"));

  private final JdbcTemplate jdbc;

  public TeamRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Locks an ACTIVE clinic for a team change; same lock mode as the V6 trigger. Contract for every
   * writer of tenant_memberships: lock the clinic first, then members, so lock order never inverts.
   * A bounded wait turns contention into a retryable 503 instead of a stuck request.
   */
  public boolean lockActiveClinic(UUID tenantId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Team changes must run inside a transaction");
    }
    jdbc.execute("SET LOCAL lock_timeout = '5s'");
    return !jdbc.query(
            "SELECT tenant_id FROM tenants WHERE tenant_id = ? AND status = 'ACTIVE'"
                + " FOR NO KEY UPDATE",
            (row, index) -> row.getObject("tenant_id", UUID.class),
            tenantId)
        .isEmpty();
  }

  /**
   * The acting member's current role, locked so a concurrent demotion of the actor waits for this
   * change. Requires the same conditions as authorization.
   */
  public Optional<ClinicRole> lockActorRole(UUID tenantId, UUID subjectId) {
    return jdbc
        .query(
            """
            SELECT m.role FROM tenant_memberships m
            JOIN user_accounts u ON u.subject_id = m.subject_id
            WHERE m.tenant_id = ? AND m.subject_id = ? AND m.active
              AND u.active AND u.email_verified
            FOR SHARE OF m
            """,
            (row, index) -> ClinicRole.valueOf(row.getString("role")),
            tenantId,
            subjectId)
        .stream()
        .findFirst();
  }

  public Optional<TeamMember> lockActiveMember(UUID tenantId, UUID subjectId) {
    return jdbc
        .query(
            COLUMNS + " WHERE m.tenant_id = ? AND m.subject_id = ? AND m.active FOR UPDATE OF m",
            MEMBER,
            tenantId,
            subjectId)
        .stream()
        .findFirst();
  }

  public List<TeamMember> list(UUID tenantId) {
    return jdbc.query(
        COLUMNS + " WHERE m.tenant_id = ? ORDER BY m.active DESC, u.display_name, m.subject_id",
        MEMBER,
        tenantId);
  }

  /** Whether another active owner (with an active, verified account) remains. */
  public boolean hasOtherActiveOwner(UUID tenantId, UUID excludedSubject) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT clinic_has_active_owner(?, ?)", Boolean.class, tenantId, excludedSubject));
  }

  public void changeRole(UUID tenantId, UUID subjectId, ClinicRole role) {
    guardLastOwner(
        () ->
            jdbc.update(
                "UPDATE tenant_memberships SET role = ? WHERE tenant_id = ? AND subject_id = ?"
                    + " AND active",
                role.name(),
                tenantId,
                subjectId));
  }

  /** Deactivates without deleting: authorship and history keep the membership (ADR-003). */
  public void deactivate(UUID tenantId, UUID subjectId) {
    guardLastOwner(
        () ->
            jdbc.update(
                "UPDATE tenant_memberships SET active = false WHERE tenant_id = ?"
                    + " AND subject_id = ? AND active",
                tenantId,
                subjectId));
  }

  private static void guardLastOwner(Runnable update) {
    try {
      update.run();
    } catch (DataAccessException exception) {
      if (exception.getMostSpecificCause() instanceof SQLException sql
          && LAST_OWNER_STATE.equals(sql.getSQLState())) {
        throw new TeamException(
            TeamException.Reason.LAST_OWNER, "A clínica precisa de ao menos um proprietário.");
      }
      throw exception;
    }
  }
}
