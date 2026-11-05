package br.com.branlycrm.provisioning.repositories;

import br.com.branlycrm.provisioning.domain.ProvisioningStep;
import br.com.branlycrm.provisioning.domain.ProvisioningTask;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task queue in the control database. Claims use {@code FOR UPDATE SKIP LOCKED} so concurrent
 * workers never take the same clinic, and an expired lease lets another worker resume a task whose
 * worker died. Every write after the claim is fenced by the attempt number.
 */
public class ProvisioningTaskRepository {
  private final JdbcTemplate jdbc;

  public ProvisioningTaskRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<ProvisioningTask> claimNext(Duration lease) {
    return jdbc
        .query(
            """
            UPDATE tenant_provisioning_tasks t
            SET status = 'RUNNING', attempts = t.attempts + 1,
                locked_until = now() + (? * interval '1 millisecond'), updated_at = now()
            FROM tenants r
            WHERE t.tenant_id = (
                SELECT q.tenant_id FROM tenant_provisioning_tasks q
                WHERE ((q.status = 'PENDING' AND q.next_attempt_at <= now())
                    OR (q.status = 'RUNNING' AND q.locked_until < now()))
                  AND EXISTS (SELECT 1 FROM tenants s
                              WHERE s.tenant_id = q.tenant_id AND s.status = 'PROVISIONING')
                ORDER BY q.next_attempt_at, q.tenant_id
                LIMIT 1
                FOR UPDATE SKIP LOCKED)
              AND r.tenant_id = t.tenant_id AND r.status = 'PROVISIONING'
            RETURNING t.tenant_id, r.database_name, r.database_user, r.credential_reference,
                      t.admin_subject_id, t.step, t.attempts, t.max_attempts
            """,
            (row, index) ->
                new ProvisioningTask(
                    new TenantDatabase(
                        row.getObject("tenant_id", UUID.class),
                        row.getString("database_name"),
                        row.getString("database_user"),
                        row.getString("credential_reference")),
                    row.getObject("admin_subject_id", UUID.class),
                    ProvisioningStep.valueOf(row.getString("step")),
                    row.getInt("attempts"),
                    row.getInt("max_attempts")),
            lease.toMillis())
        .stream()
        .findFirst();
  }

  /** Records progress and renews the lease; false means another worker owns the task now. */
  public boolean advance(ProvisioningTask task, ProvisioningStep next, Duration lease) {
    return jdbc.update(
            """
            UPDATE tenant_provisioning_tasks
            SET step = ?, locked_until = now() + (? * interval '1 millisecond'), updated_at = now()
            WHERE tenant_id = ? AND attempts = ? AND status = 'RUNNING'
            """,
            next.name(),
            lease.toMillis(),
            task.tenantId(),
            task.attempt())
        == 1;
  }

  public boolean complete(ProvisioningTask task) {
    return jdbc.update(
            """
            UPDATE tenant_provisioning_tasks
            SET status = 'DONE', step = 'DONE', locked_until = NULL, last_error = NULL,
                updated_at = now()
            WHERE tenant_id = ? AND attempts = ? AND status = 'RUNNING'
            """,
            task.tenantId(),
            task.attempt())
        == 1;
  }

  /** Schedules a retry after the backoff, keeping the recorded step for resumption. */
  public boolean retryLater(ProvisioningTask task, String code, Duration backoff) {
    return jdbc.update(
            """
            UPDATE tenant_provisioning_tasks
            SET status = 'PENDING', locked_until = NULL, last_error = ?,
                next_attempt_at = now() + (? * interval '1 millisecond'), updated_at = now()
            WHERE tenant_id = ? AND attempts = ? AND status = 'RUNNING'
            """,
            code,
            backoff.toMillis(),
            task.tenantId(),
            task.attempt())
        == 1;
  }

  public boolean failPermanently(ProvisioningTask task, String code) {
    return jdbc.update(
            """
            UPDATE tenant_provisioning_tasks
            SET status = 'FAILED', locked_until = NULL, last_error = ?, updated_at = now()
            WHERE tenant_id = ? AND attempts = ? AND status = 'RUNNING'
            """,
            code,
            task.tenantId(),
            task.attempt())
        == 1;
  }

  /** Administrative resumption: same UUID and names, attempts restart, step is preserved. */
  public boolean resetFailed(UUID tenantId) {
    return jdbc.update(
            """
            UPDATE tenant_provisioning_tasks
            SET status = 'PENDING', attempts = 0, next_attempt_at = now(), updated_at = now()
            WHERE tenant_id = ? AND status = 'FAILED'
            """,
            tenantId)
        == 1;
  }
}
