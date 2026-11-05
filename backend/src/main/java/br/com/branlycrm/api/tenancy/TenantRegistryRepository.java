package br.com.branlycrm.api.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TenantRegistryRepository {
    private final JdbcTemplate database;
    public TenantRegistryRepository(JdbcTemplate database) { this.database = database; }

    public Optional<TenantAccessService.TenantDatabase> findAuthorized(UUID tenantId, UUID subjectId) {
        return database.query("""
            SELECT t.tenant_id, t.database_name, t.database_user, t.credential_reference
            FROM tenants t JOIN tenant_memberships m ON m.tenant_id = t.tenant_id
            JOIN user_accounts u ON u.subject_id = m.subject_id
            WHERE t.tenant_id = ? AND m.subject_id = ? AND m.active AND t.status = 'ACTIVE' AND u.active AND u.email_verified
            """, (rs, row) -> new TenantAccessService.TenantDatabase(
                rs.getObject("tenant_id", UUID.class), rs.getString("database_name"),
                rs.getString("database_user"), rs.getString("credential_reference")),
            tenantId, subjectId).stream().findFirst();
    }

    public List<Membership> findMemberships(UUID subjectId) {
        return database.query("""
            SELECT t.tenant_id, t.clinic_name, m.role FROM tenants t
            JOIN tenant_memberships m ON m.tenant_id = t.tenant_id
            JOIN user_accounts u ON u.subject_id = m.subject_id
            WHERE m.subject_id = ? AND m.active AND t.status = 'ACTIVE' AND u.active AND u.email_verified
            ORDER BY t.clinic_name, t.tenant_id
            """, (rs, row) -> new Membership(rs.getObject("tenant_id", UUID.class),
                rs.getString("clinic_name"), rs.getString("role")), subjectId);
    }
    public record Membership(UUID tenantId, String clinicName, String role) {}
}