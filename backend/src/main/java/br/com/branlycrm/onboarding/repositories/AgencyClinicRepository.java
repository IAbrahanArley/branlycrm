package br.com.branlycrm.onboarding.repositories;

import br.com.branlycrm.tenant.domain.TenantDatabase;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Registry writes of the agency portfolio (V11 grants): a clinic row with derived names and no
 * status (it starts PROVISIONING), and its provisioning task with no admin (ADR-028). The
 * provisioner process does the rest; nothing here touches a clinic database.
 */
@Repository
public class AgencyClinicRepository {
  private final JdbcTemplate jdbc;

  public AgencyClinicRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** A clinic already created for this request in this agency, with the owner it invited. */
  public record ExistingRequest(
      UUID tenantId, String clinicName, String status, String ownerEmail) {}

  public Optional<ExistingRequest> findByRequest(UUID agencyId, UUID requestId) {
    return jdbc
        .query(
            """
            SELECT t.tenant_id, t.clinic_name, t.status,
                   (SELECT i.email FROM invitations i
                    WHERE i.tenant_id = t.tenant_id AND i.issuer_agency_id = t.agency_id
                    ORDER BY i.created_at LIMIT 1) AS owner_email
            FROM tenants t WHERE t.agency_id = ? AND t.agency_request_id = ?
            """,
            (row, index) ->
                new ExistingRequest(
                    row.getObject("tenant_id", UUID.class),
                    row.getString("clinic_name"),
                    row.getString("status"),
                    row.getString("owner_email")),
            agencyId,
            requestId)
        .stream()
        .findFirst();
  }

  /**
   * Empty when the same request already created a clinic concurrently: the unique key on
   * (agency_id, agency_request_id) makes this insert wait for the other one and then skip.
   */
  public boolean insertClinic(
      TenantDatabase database, String clinicName, UUID agencyId, UUID requestId) {
    return !jdbc.query(
            """
            INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,
                                 credential_reference, agency_id, agency_request_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (agency_id, agency_request_id) DO NOTHING
            RETURNING tenant_id
            """,
            (row, index) -> row.getObject("tenant_id", UUID.class),
            database.tenantId(),
            clinicName,
            database.databaseName(),
            database.databaseUser(),
            database.credentialReference(),
            agencyId,
            requestId)
        .isEmpty();
  }

  public void insertProvisioningTask(UUID tenantId) {
    jdbc.update("INSERT INTO tenant_provisioning_tasks (tenant_id) VALUES (?)", tenantId);
  }

  /**
   * Locks a clinic of the agency's portfolio and returns its status; empty for a clinic of another
   * agency, a direct clinic or a missing one, which answer alike. Same lock as the acceptance and
   * the provisioner (ADR-028).
   */
  public Optional<String> lockPortfolioClinic(UUID agencyId, UUID tenantId) {
    jdbc.execute("SET LOCAL lock_timeout = '5s'");
    return jdbc
        .query(
            "SELECT status FROM tenants WHERE tenant_id = ? AND agency_id = ? FOR NO KEY UPDATE",
            (row, index) -> row.getString("status"),
            tenantId,
            agencyId)
        .stream()
        .findFirst();
  }

  public Optional<String> agencyName(UUID agencyId) {
    return jdbc
        .query(
            "SELECT name FROM agencies WHERE agency_id = ?",
            (row, index) -> row.getString("name"),
            agencyId)
        .stream()
        .findFirst();
  }
}
