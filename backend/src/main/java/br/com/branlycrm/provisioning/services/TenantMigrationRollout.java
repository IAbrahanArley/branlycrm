package br.com.branlycrm.provisioning.services;

import br.com.branlycrm.provisioning.domain.RolloutOutcome;
import br.com.branlycrm.provisioning.domain.RolloutOutcome.Stage;
import br.com.branlycrm.provisioning.exceptions.ProvisioningException;
import br.com.branlycrm.provisioning.repositories.TenantInfrastructureRepository;
import br.com.branlycrm.provisioning.repositories.TenantRolloutRepository;
import br.com.branlycrm.provisioning.repositories.TenantRuntimeProbeRepository;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.secrets.TenantSecretException;
import br.com.branlycrm.tenant.secrets.TenantSecretReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.dao.DataAccessException;

/**
 * Schema rollout for existing clinics (ADR-031), run once by {@code APP_MODE=tenant-migrate}:
 *
 * <pre>
 * (no arguments)        every ACTIVE, AWAITING_OWNER and SUSPENDED clinic
 * --tenant-id UUID      only that clinic, if it is eligible
 * </pre>
 *
 * For each clinic, one at a time: a read-only precheck (database, owner, roles, identity), the
 * db/tenant-migration migrations as {@code owner_<id>}, and the same runtime verification as the
 * provisioner. A failure is reported with a stable code and the next clinic still runs. Running
 * again resumes: Flyway skips what is applied and locks each database, so a parallel run or the
 * provisioner never applies a migration twice.
 *
 * <p>Output is one line per clinic plus a summary on standard output; nothing here throws, so no
 * stack trace with PostgreSQL text reaches the console. Exit code 0 when every clinic succeeded, 1
 * when any failed or the control database was unreachable, 2 on invalid usage.
 */
public class TenantMigrationRollout implements CommandLineRunner, ExitCodeGenerator {
  private static final Logger log = LoggerFactory.getLogger(TenantMigrationRollout.class);
  static final int FAILED = 1;
  static final int USAGE = 2;
  static final String USAGE_TEXT = "Usage: tenant-migrate [--tenant-id UUID]";

  private final TenantRolloutRepository clinics;
  private final TenantInfrastructureRepository infrastructure;
  private final TenantRuntimeProbeRepository probe;
  private final TenantSecretReader secrets;
  private final List<String> forbiddenDatabases;
  private final PrintStream out;
  private volatile int exitCode;

  public TenantMigrationRollout(
      TenantRolloutRepository clinics,
      TenantInfrastructureRepository infrastructure,
      TenantRuntimeProbeRepository probe,
      TenantSecretReader secrets,
      List<String> forbiddenDatabases,
      PrintStream out) {
    this.clinics = clinics;
    this.infrastructure = infrastructure;
    this.probe = probe;
    this.secrets = secrets;
    this.forbiddenDatabases = List.copyOf(forbiddenDatabases);
    this.out = out;
  }

  @Override
  public void run(String... args) {
    UUID only;
    try {
      only = selection(args);
    } catch (IllegalArgumentException invalid) {
      out.println(USAGE_TEXT);
      exitCode = USAGE;
      return;
    }
    List<RolloutOutcome> outcomes = new ArrayList<>();
    try {
      if (only == null) {
        for (TenantDatabase database : clinics.eligible()) outcomes.add(rollOut(database));
      } else {
        outcomes.add(
            clinics
                .eligible(only)
                .map(this::rollOut)
                .orElseGet(
                    () -> RolloutOutcome.failed(only, Stage.SELECTION, 0, "TENANT_NOT_ELIGIBLE")));
      }
    } catch (DataAccessException unavailable) {
      // The clinics already processed are reported; the rest runs again on the next execution.
      log.warn("Tenant rollout aborted: control database unavailable");
      outcomes.forEach(outcome -> out.println(outcome.line()));
      out.println("summary: aborted code=CONTROL_UNAVAILABLE");
      exitCode = FAILED;
      return;
    }
    outcomes.forEach(outcome -> out.println(outcome.line()));
    long failed = outcomes.stream().filter(RolloutOutcome::failed).count();
    long migrated =
        outcomes.stream().filter(o -> o.result() == RolloutOutcome.Result.MIGRATED).count();
    out.println(
        "summary: clinics="
            + outcomes.size()
            + " migrated="
            + migrated
            + " upToDate="
            + (outcomes.size() - migrated - failed)
            + " failed="
            + failed);
    exitCode = failed == 0 ? 0 : FAILED;
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }

  /** Migrates before reading the secret: a missing secret must not keep the schema outdated. */
  RolloutOutcome rollOut(TenantDatabase database) {
    Stage stage = Stage.PRECHECK;
    int applied = 0;
    try {
      infrastructure.requireProvisioned(database);
      stage = Stage.MIGRATION;
      applied = infrastructure.migrate(database);
      stage = Stage.VERIFICATION;
      String password =
          secrets
              .read(database)
              .orElseThrow(() -> new ProvisioningException("SECRET_MISSING"))
              .password();
      probe.verify(database, password, forbiddenDatabases);
      log.info("Tenant {} schema rollout finished, {} applied", database.tenantId(), applied);
      return RolloutOutcome.succeeded(database.tenantId(), applied);
    } catch (ProvisioningException failure) {
      return failed(database, stage, applied, failure.code());
    } catch (TenantSecretException failure) {
      return failed(database, stage, applied, "SECRET_" + failure.reason().name());
    } catch (RuntimeException failure) {
      return failed(database, stage, applied, stage.name() + "_FAILED");
    }
  }

  private static RolloutOutcome failed(
      TenantDatabase database, Stage stage, int applied, String code) {
    // Only the stable code is logged: causes may carry SQL text from PostgreSQL.
    log.warn("Tenant {} schema rollout failed at {} with {}", database.tenantId(), stage, code);
    return RolloutOutcome.failed(database.tenantId(), stage, applied, code);
  }

  /** No arguments, or exactly {@code --tenant-id UUID}. */
  private static UUID selection(String[] args) {
    if (args.length == 0) return null;
    if (args.length != 2 || !"--tenant-id".equals(args[0])) {
      throw new IllegalArgumentException("usage");
    }
    UUID id = UUID.fromString(args[1]);
    // UUID.fromString accepts short forms such as "1-1-1-1-1"; only the canonical one is valid.
    if (!id.toString().equals(args[1].toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("usage");
    }
    return id;
  }
}
