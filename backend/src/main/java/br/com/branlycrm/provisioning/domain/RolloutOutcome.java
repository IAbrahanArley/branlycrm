package br.com.branlycrm.provisioning.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Result of the schema rollout for one clinic (ADR-031). Carries only the clinic UUID, the stage
 * and a stable code: never SQL text, secrets or personal data, so it is safe to print and log.
 *
 * @param applied migrations applied in this run; on a VERIFICATION failure it tells the operator
 *     the schema is already up to date and a new run only repeats the check.
 */
public record RolloutOutcome(UUID tenantId, Result result, Stage stage, int applied, String code) {

  public enum Result {
    MIGRATED,
    UP_TO_DATE,
    FAILED
  }

  public enum Stage {
    SELECTION,
    PRECHECK,
    MIGRATION,
    VERIFICATION
  }

  public RolloutOutcome {
    Objects.requireNonNull(tenantId);
    Objects.requireNonNull(result);
    Objects.requireNonNull(stage);
    if (applied < 0) throw new IllegalArgumentException("applied");
    if ((result == Result.FAILED) != (code != null)) {
      throw new IllegalArgumentException("Only failures carry a code");
    }
    if (code != null && !code.matches("[A-Z_]{1,60}")) {
      throw new IllegalArgumentException("Invalid rollout code");
    }
  }

  public static RolloutOutcome succeeded(UUID tenantId, int applied) {
    return new RolloutOutcome(
        tenantId,
        applied == 0 ? Result.UP_TO_DATE : Result.MIGRATED,
        Stage.VERIFICATION,
        applied,
        null);
  }

  public static RolloutOutcome failed(UUID tenantId, Stage stage, int applied, String code) {
    return new RolloutOutcome(tenantId, Result.FAILED, stage, applied, code);
  }

  public boolean failed() {
    return result == Result.FAILED;
  }

  /** One report line: {@code tenantId=<uuid> result=<R> stage=<S> applied=<n> [code=<C>]}. */
  public String line() {
    String line =
        "tenantId=" + tenantId + " result=" + result + " stage=" + stage + " applied=" + applied;
    return code == null ? line : line + " code=" + code;
  }
}
