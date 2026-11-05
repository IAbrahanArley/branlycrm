package br.com.branlycrm.tenant.domain;

import static br.com.branlycrm.tenant.domain.AccessScope.ALL;
import static br.com.branlycrm.tenant.domain.AccessScope.NONE;
import static br.com.branlycrm.tenant.domain.AccessScope.OWN;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Clinic roles of ADR-020. One role per membership; a role is never a global authority. The matrix
 * below is the executable form of docs/permissoes.md and is tested row by row: change both
 * together. Anything not listed is {@link AccessScope#NONE}.
 */
public enum ClinicRole {
  OWNER,
  MANAGER,
  RECEPTION,
  SALES,
  PROFESSIONAL;

  private static final Map<ClinicRole, Map<ClinicPermission, AccessScope>> MATRIX =
      new EnumMap<>(ClinicRole.class);

  static {
    for (ClinicRole role : values()) MATRIX.put(role, new EnumMap<>(ClinicPermission.class));
    //                                         OWNER MANAGER RECEPTION SALES PROFESSIONAL
    row(ClinicPermission.CLINIC_SETTINGS_VIEW, ALL, ALL, ALL, ALL, ALL);
    row(ClinicPermission.CLINIC_SETTINGS_EDIT, ALL, NONE, NONE, NONE, NONE);
    row(ClinicPermission.SUBSCRIPTION_VIEW, ALL, NONE, NONE, NONE, NONE);
    row(ClinicPermission.TEAM_VIEW, ALL, ALL, NONE, NONE, NONE);
    // MANAGER reaches only operational members; see TeamPolicy.
    row(ClinicPermission.TEAM_MANAGE, ALL, ALL, NONE, NONE, NONE);
    row(ClinicPermission.PIPELINE_VIEW, ALL, ALL, ALL, ALL, NONE);
    row(ClinicPermission.PIPELINE_EDIT, ALL, ALL, NONE, NONE, NONE);
    row(ClinicPermission.CONTACT_CREATE, ALL, ALL, ALL, ALL, NONE);
    row(ClinicPermission.CONTACT_READ, ALL, ALL, ALL, OWN, NONE);
    // Name and phone only, of contacts in the professional's own appointments.
    row(ClinicPermission.CONTACT_READ_BASIC, ALL, ALL, ALL, OWN, OWN);
    row(ClinicPermission.CONTACT_EDIT, ALL, ALL, ALL, OWN, NONE);
    row(ClinicPermission.CONTACT_ARCHIVE, ALL, ALL, ALL, NONE, NONE);
    row(ClinicPermission.OPPORTUNITY_CREATE, ALL, ALL, ALL, ALL, NONE);
    // RECEPTION picks the responsible seller only when creating; SALES creates for itself.
    row(ClinicPermission.OPPORTUNITY_ASSIGN_ON_CREATE, ALL, ALL, ALL, NONE, NONE);
    row(ClinicPermission.OPPORTUNITY_READ, ALL, ALL, ALL, OWN, NONE);
    row(ClinicPermission.OPPORTUNITY_UPDATE, ALL, ALL, NONE, OWN, NONE);
    row(ClinicPermission.OPPORTUNITY_CLOSE, ALL, ALL, NONE, OWN, NONE);
    row(ClinicPermission.OPPORTUNITY_REASSIGN, ALL, ALL, NONE, NONE, NONE);
    row(ClinicPermission.ACTIVITY_WRITE, ALL, ALL, ALL, OWN, NONE);
    row(ClinicPermission.TASK_CREATE_OWN, ALL, ALL, ALL, ALL, ALL);
    row(ClinicPermission.TASK_ASSIGN, ALL, ALL, ALL, NONE, NONE);
    row(ClinicPermission.TASK_READ, ALL, ALL, OWN, OWN, OWN);
    row(ClinicPermission.APPOINTMENT_MANAGE, ALL, ALL, ALL, OWN, NONE);
    row(ClinicPermission.APPOINTMENT_READ, ALL, ALL, ALL, OWN, OWN);
    row(ClinicPermission.APPOINTMENT_ATTENDANCE, ALL, ALL, ALL, NONE, OWN);
    row(ClinicPermission.REPORTS_VIEW, ALL, ALL, NONE, OWN, NONE);
    row(ClinicPermission.DATA_EXPORT, ALL, NONE, NONE, NONE, NONE);
    row(ClinicPermission.AUDIT_VIEW, ALL, NONE, NONE, NONE, NONE);
  }

  private static void row(
      ClinicPermission permission,
      AccessScope owner,
      AccessScope manager,
      AccessScope reception,
      AccessScope sales,
      AccessScope professional) {
    MATRIX.get(OWNER).put(permission, owner);
    MATRIX.get(MANAGER).put(permission, manager);
    MATRIX.get(RECEPTION).put(permission, reception);
    MATRIX.get(SALES).put(permission, sales);
    MATRIX.get(PROFESSIONAL).put(permission, professional);
  }

  public AccessScope scope(ClinicPermission permission) {
    return MATRIX.get(this).getOrDefault(permission, NONE);
  }

  /** Every permission with its scope, for UI hints. The server still decides each request. */
  public Map<ClinicPermission, AccessScope> permissions() {
    return Collections.unmodifiableMap(MATRIX.get(this));
  }

  /** Roles a MANAGER may assign or manage: never OWNER nor MANAGER (ADR-020). */
  public boolean operational() {
    return this == RECEPTION || this == SALES || this == PROFESSIONAL;
  }
}
