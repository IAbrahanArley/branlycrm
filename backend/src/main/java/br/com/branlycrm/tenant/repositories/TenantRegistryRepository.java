package br.com.branlycrm.tenant.repositories;

import br.com.branlycrm.tenant.domain.ClinicMembership;
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

  public Optional<TenantDatabase> findAuthorized(UUID tenantId, UUID subjectId) {
    return jdbc
        .query(
            """
            SELECT t.tenant_id, t.database_name, t.database_user, t.credential_reference
            FROM tenants t
            JOIN tenant_memberships m ON m.tenant_id = t.tenant_id
            JOIN user_accounts u ON u.subject_id = m.subject_id
            WHERE t.tenant_id = ? AND m.subject_id = ?
              AND m.active AND t.status = 'ACTIVE' AND u.active AND u.email_verified
            """,
            (row, index) ->
                new TenantDatabase(
                    row.getObject("tenant_id", UUID.class),
                    row.getString("database_name"),
                    row.getString("database_user"),
                    row.getString("credential_reference")),
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
                row.getString("role")),
        subjectId);
  }
}
