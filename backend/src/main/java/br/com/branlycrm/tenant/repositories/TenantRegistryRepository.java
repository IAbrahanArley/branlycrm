package br.com.branlycrm.tenant.repositories;

import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicMembership;
import br.com.branlycrm.tenant.domain.ClinicRole;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Control database registry. Every read requires active clinic, active membership and an active,
 * verified identity, so authorization holds outside the HTTP filter too.
 */
@Repository
public class TenantRegistryRepository {
  private final JdbcTemplate jdbc;

  public TenantRegistryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Access with the role, read in the same query that checks clinic, membership and account. */
  public Optional<ClinicAccess> findAuthorized(UUID tenantId, UUID subjectId) {
    return jdbc
        .query(
            """
            SELECT t.tenant_id, t.database_name, t.database_user, t.credential_reference, m.role
            FROM tenants t
            JOIN tenant_memberships m ON m.tenant_id = t.tenant_id
            JOIN user_accounts u ON u.subject_id = m.subject_id
            WHERE t.tenant_id = ? AND m.subject_id = ?
              AND m.active AND t.status = 'ACTIVE' AND u.active AND u.email_verified
            """,
            (row, index) ->
                new ClinicAccess(
                    new TenantDatabase(
                        row.getObject("tenant_id", UUID.class),
                        row.getString("database_name"),
                        row.getString("database_user"),
                        row.getString("credential_reference")),
                    subjectId,
                    ClinicRole.valueOf(row.getString("role"))),
            tenantId,
            subjectId)
        .stream()
        .findFirst();
  }

  public List<ClinicMembership> findMemberships(UUID subjectId) {
    return jdbc.query(
        """
        SELECT t.tenant_id, t.clinic_name, m.role
        FROM tenants t
        JOIN tenant_memberships m ON m.tenant_id = t.tenant_id
        JOIN user_accounts u ON u.subject_id = m.subject_id
        WHERE m.subject_id = ?
          AND m.active AND t.status = 'ACTIVE' AND u.active AND u.email_verified
        ORDER BY t.clinic_name, t.tenant_id
        """,
        (row, index) ->
            new ClinicMembership(
                row.getObject("tenant_id", UUID.class),
                row.getString("clinic_name"),
                ClinicRole.valueOf(row.getString("role"))),
        subjectId);
  }
}
