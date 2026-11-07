package br.com.branlycrm.provisioning.domain;

import br.com.branlycrm.tenant.domain.TenantDatabase;
import java.util.UUID;

/**
 * Claimed task. {@code attempt} doubles as a fencing token: every later write must match it, so a
 * worker whose lease expired cannot overwrite the progress of the worker that reclaimed the task.
 *
 * <p>{@code adminSubjectId} is {@code null} for clinics whose first owner arrives by invitation
 * (ADR-028): activation then waits for that owner instead of creating one.
 */
public record ProvisioningTask(
    TenantDatabase database,
    UUID adminSubjectId,
    ProvisioningStep step,
    int attempt,
    int maxAttempts) {

  public UUID tenantId() {
    return database.tenantId();
  }

  public boolean awaitsInvitedOwner() {
    return adminSubjectId == null;
  }

  public boolean exhausted() {
    return attempt >= maxAttempts;
  }
}
