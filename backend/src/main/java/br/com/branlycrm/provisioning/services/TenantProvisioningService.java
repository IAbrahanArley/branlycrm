package br.com.branlycrm.provisioning.services;

import br.com.branlycrm.provisioning.domain.ProvisioningStep;
import br.com.branlycrm.provisioning.domain.ProvisioningTask;
import br.com.branlycrm.provisioning.exceptions.ProvisioningException;
import br.com.branlycrm.provisioning.repositories.ProvisioningRegistryRepository;
import br.com.branlycrm.provisioning.repositories.ProvisioningTaskRepository;
import br.com.branlycrm.provisioning.repositories.TenantInfrastructureRepository;
import br.com.branlycrm.provisioning.repositories.TenantRuntimeProbeRepository;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.domain.TenantSecret;
import br.com.branlycrm.tenant.secrets.FileTenantSecretWriter;
import br.com.branlycrm.tenant.secrets.TenantSecretException;
import br.com.branlycrm.tenant.secrets.TenantSecretReader;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates one clinic environment (ADR-014): secret, roles, database, migrations, verification and,
 * only after every check passed, the ADMIN membership and the ACTIVE status in one control
 * transaction. Each step is idempotent and the task records the last finished one, so a retry
 * resumes with the same UUID and never duplicates roles, databases or memberships. A failure never
 * drops a database: compensation is a manual, administrative decision.
 */
public class TenantProvisioningService {
  private static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ProvisioningTaskRepository tasks;
  private final ProvisioningRegistryRepository registry;
  private final TenantInfrastructureRepository infrastructure;
  private final TenantRuntimeProbeRepository probe;
  private final TenantSecretReader secrets;
  private final FileTenantSecretWriter writer;
  private final TransactionTemplate transactions;
  private final List<String> forbiddenDatabases;
  private final Duration lease;
  private final Duration backoff;

  public TenantProvisioningService(
      ProvisioningTaskRepository tasks,
      ProvisioningRegistryRepository registry,
      TenantInfrastructureRepository infrastructure,
      TenantRuntimeProbeRepository probe,
      TenantSecretReader secrets,
      FileTenantSecretWriter writer,
      TransactionTemplate transactions,
      List<String> forbiddenDatabases,
      Duration lease,
      Duration backoff) {
    this.tasks = tasks;
    this.registry = registry;
    this.infrastructure = infrastructure;
    this.probe = probe;
    this.secrets = secrets;
    this.writer = writer;
    this.transactions = transactions;
    this.forbiddenDatabases = List.copyOf(forbiddenDatabases);
    this.lease = lease;
    this.backoff = backoff;
  }

  /** Processes every task that is ready now; returns how many were claimed. */
  public int runPending() {
    int claimed = 0;
    for (var task = tasks.claimNext(lease); task.isPresent(); task = tasks.claimNext(lease)) {
      claimed++;
      process(task.get());
    }
    return claimed;
  }

  /** Administrative resumption of a FAILED clinic, keeping its UUID and completed steps. */
  public boolean retryFailed(UUID tenantId) {
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              if (!registry.changeStatus(tenantId, "FAILED", "PROVISIONING")
                  || !tasks.resetFailed(tenantId)) {
                status.setRollbackOnly();
                return false;
              }
              return true;
            }));
  }

  private void process(ProvisioningTask task) {
    ProvisioningStep step = task.step();
    try {
      while (step != ProvisioningStep.ACTIVATION) {
        execute(step, task.database());
        step = step.next();
        if (!tasks.advance(task, step, lease)) return; // another worker reclaimed the task
      }
      activate(task);
      log.info("Tenant {} provisioned", task.tenantId());
    } catch (ProvisioningException failure) {
      fail(task, step, failure.code());
    } catch (RuntimeException failure) {
      fail(task, step, step.name() + "_FAILED");
    }
  }

  private void execute(ProvisioningStep step, TenantDatabase database) {
    switch (step) {
      case SECRET -> publishSecret(database);
      case ROLES -> infrastructure.ensureRoles(database, secret(database).password());
      case DATABASE -> infrastructure.ensureDatabase(database);
      case MIGRATION -> infrastructure.migrate(database);
      case VERIFICATION -> probe.verify(database, secret(database).password(), forbiddenDatabases);
      default -> throw new IllegalStateException("Unexpected step " + step);
    }
  }

  /** Reuses an existing secret so a retry never diverges from the role password already set. */
  private void publishSecret(TenantDatabase database) {
    if (secrets.read(database).isPresent()) return;
    try {
      writer.create(database, generatePassword());
    } catch (TenantSecretException exception) {
      if (exception.reason() != TenantSecretException.Reason.ALREADY_EXISTS) throw exception;
    }
  }

  private TenantSecret secret(TenantDatabase database) {
    return secrets.read(database).orElseThrow(() -> new ProvisioningException("SECRET_MISSING"));
  }

  private void activate(ProvisioningTask task) {
    transactions.executeWithoutResult(
        status -> {
          if (!registry.isEligibleAdmin(task.adminSubjectId())) {
            throw new ProvisioningException("ADMIN_NOT_ELIGIBLE");
          }
          registry.addAdmin(task.tenantId(), task.adminSubjectId());
          // ON CONFLICT DO NOTHING keeps an existing membership; it must be an active OWNER.
          if (!registry.isActiveOwner(task.tenantId(), task.adminSubjectId())) {
            throw new ProvisioningException("ADMIN_NOT_OWNER");
          }
          if (!registry.changeStatus(task.tenantId(), "PROVISIONING", "ACTIVE")) {
            throw new ProvisioningException("TENANT_NOT_PROVISIONING");
          }
          if (!tasks.complete(task)) throw new ProvisioningException("LEASE_LOST");
        });
  }

  private void fail(ProvisioningTask task, ProvisioningStep step, String code) {
    // Only the stable code is logged: causes may carry SQL text from PostgreSQL.
    log.warn("Tenant {} provisioning failed at {} with {}", task.tenantId(), step, code);
    if (!task.exhausted()) {
      tasks.retryLater(task, code, backoff.multipliedBy(task.attempt()));
      return;
    }
    transactions.executeWithoutResult(
        status -> {
          if (tasks.failPermanently(task, code)) {
            registry.changeStatus(task.tenantId(), "PROVISIONING", "FAILED");
          }
        });
  }

  /** 32 random bytes, base64url: 43 characters within the stored secret alphabet. */
  static String generatePassword() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
