package br.com.branlycrm.provisioning.domain;

/** Ordered, individually idempotent steps; a retry resumes from the last recorded step. */
public enum ProvisioningStep {
  SECRET,
  ROLES,
  DATABASE,
  MIGRATION,
  VERIFICATION,
  ACTIVATION,
  DONE;

  public ProvisioningStep next() {
    if (this == DONE) throw new IllegalStateException("Provisioning already finished");
    return values()[ordinal() + 1];
  }
}
