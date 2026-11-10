package br.com.branlycrm.provisioning.repositories;

import br.com.branlycrm.tenant.domain.TenantDatabase;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Read-only selection of the clinics the schema rollout reaches (ADR-031): every clinic whose
 * database was already handed over by the provisioner. PROVISIONING and FAILED stay with the
 * provisioning queue, which migrates them itself. Uses the SELECT the worker already holds on
 * {@code tenants} (V5); writes nothing.
 */
public class TenantRolloutRepository {
  private static final String ELIGIBLE = "('ACTIVE', 'AWAITING_OWNER', 'SUSPENDED')";
  private static final RowMapper<TenantDatabase> DATABASE =
      (row, index) ->
          new TenantDatabase(
              row.getObject("tenant_id", UUID.class),
              row.getString("database_name"),
              row.getString("database_user"),
              row.getString("credential_reference"));

  private final JdbcTemplate jdbc;

  public TenantRolloutRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Stable order, so two runs report the clinics in the same sequence. */
  public List<TenantDatabase> eligible() {
    return jdbc.query(
        "SELECT tenant_id, database_name, database_user, credential_reference FROM tenants"
            + " WHERE status IN "
            + ELIGIBLE
            + " ORDER BY tenant_id",
        DATABASE);
  }

  public Optional<TenantDatabase> eligible(UUID tenantId) {
    return jdbc
        .query(
            "SELECT tenant_id, database_name, database_user, credential_reference FROM tenants"
                + " WHERE tenant_id = ? AND status IN "
                + ELIGIBLE,
            DATABASE,
            tenantId)
        .stream()
        .findFirst();
  }
}
