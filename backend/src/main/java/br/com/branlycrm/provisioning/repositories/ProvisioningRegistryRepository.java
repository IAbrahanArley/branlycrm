package br.com.branlycrm.provisioning.repositories;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Control registry writes the worker is allowed to make: clinic status and the first admin. */
public class ProvisioningRegistryRepository {
  private final JdbcTemplate jdbc;

  public ProvisioningRegistryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Reads only account state columns; the worker role cannot see password hashes. */
  public boolean isEligibleAdmin(UUID subjectId) {
    return !jdbc.query(
            "SELECT subject_id FROM user_accounts WHERE subject_id = ? AND active AND email_verified",
            (row, index) -> row.getObject("subject_id", UUID.class),
            subjectId)
        .isEmpty();
  }

  public void addAdmin(UUID tenantId, UUID subjectId) {
    jdbc.update(
        """
        INSERT INTO tenant_memberships (tenant_id, subject_id, role)
        VALUES (?, ?, 'OWNER')
        ON CONFLICT (tenant_id, subject_id) DO NOTHING
        """,
        tenantId,
        subjectId);
  }

  /** True when the admin's membership exists, is active and is OWNER (ADR-020). */
  public boolean isActiveOwner(UUID tenantId, UUID subjectId) {
    return !jdbc.query(
            "SELECT subject_id FROM tenant_memberships"
                + " WHERE tenant_id = ? AND subject_id = ? AND active AND role = 'OWNER'",
            (row, index) -> row.getObject("subject_id", UUID.class),
            tenantId,
            subjectId)
        .isEmpty();
  }

  public boolean changeStatus(UUID tenantId, String from, String to) {
    return jdbc.update(
            "UPDATE tenants SET status = ? WHERE tenant_id = ? AND status = ?", to, tenantId, from)
        == 1;
  }
}
