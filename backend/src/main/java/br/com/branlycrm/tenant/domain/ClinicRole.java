package br.com.branlycrm.tenant.domain;

import static br.com.branlycrm.tenant.domain.AccessScope.ALL;
import static br.com.branlycrm.tenant.domain.AccessScope.NONE;
import static br.com.branlycrm.tenant.domain.AccessScope.OWN;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Clinic roles of ADR-025: Gestor ({@code OWNER}, {@code MANAGER}), Atendente ({@code ATTENDANT})
 * and Somente leitura ({@code VIEWER}). One role per membership; a role is never a global
 * authority. The matrix below is the executable form of docs/permissoes.md and is tested row by
 * row: change both together. Anything not listed is {@link AccessScope#NONE}.
 */
public enum ClinicRole {
  OWNER,
  MANAGER,
  ATTENDANT,
  VIEWER;

  private static final Map<ClinicRole, Map<ClinicPermission, AccessScope>> MATRIX =
      new EnumMap<>(ClinicRole.class);

  static {
    for (ClinicRole role : values()) MATRIX.put(role, new EnumMap<>(ClinicPermission.class));
    //                                             OWNER MANAGER ATTENDANT VIEWER
    row(ClinicPermission.CLINIC_SETTINGS_VIEW, ALL, ALL, ALL, ALL);
    row(ClinicPermission.CLINIC_SETTINGS_EDIT, ALL, NONE, NONE, NONE);
    row(ClinicPermission.SUBSCRIPTION_VIEW, ALL, NONE, NONE, NONE);
    row(ClinicPermission.TEAM_VIEW, ALL, ALL, NONE, NONE);
    // MANAGER reaches only operational members; see TeamPolicy.
    row(ClinicPermission.TEAM_MANAGE, ALL, ALL, NONE, NONE);
    row(ClinicPermission.COMMERCIAL_SETTINGS_EDIT, ALL, ALL, NONE, NONE);
    row(ClinicPermission.CHANNEL_VIEW, ALL, ALL, NONE, NONE);
    row(ClinicPermission.CONTACT_READ, ALL, ALL, ALL, ALL);
    row(ClinicPermission.CONTACT_WRITE, ALL, ALL, ALL, NONE);
    row(ClinicPermission.CONTACT_ARCHIVE, ALL, ALL, NONE, NONE);
    row(ClinicPermission.TAG_MANAGE, ALL, ALL, NONE, NONE);
    row(ClinicPermission.CONTACT_IMPORT, ALL, ALL, NONE, NONE);
    row(ClinicPermission.LEAD_READ, ALL, ALL, ALL, ALL);
    row(ClinicPermission.LEAD_WRITE, ALL, ALL, ALL, NONE);
    // An attendant takes a lead for itself; assigning it to someone else is LEAD_ASSIGN.
    row(ClinicPermission.LEAD_TAKE, ALL, ALL, OWN, NONE);
    row(ClinicPermission.LEAD_ASSIGN, ALL, ALL, NONE, NONE);
    row(ClinicPermission.LEAD_ORIGIN_EDIT, ALL, ALL, ALL, NONE);
    row(ClinicPermission.SALE_REGISTER, ALL, ALL, ALL, NONE);
    row(ClinicPermission.SALE_CORRECT, ALL, ALL, NONE, NONE);
    row(ClinicPermission.INBOX_READ, ALL, ALL, ALL, ALL);
    row(ClinicPermission.INBOX_REPLY, ALL, ALL, ALL, NONE);
    row(ClinicPermission.TASK_WRITE, ALL, ALL, ALL, NONE);
    row(ClinicPermission.TASK_READ, ALL, ALL, ALL, ALL);
    row(ClinicPermission.NOTIFICATIONS_VIEW, ALL, ALL, ALL, ALL);
    row(ClinicPermission.LEAD_LIST_VIEW, ALL, ALL, ALL, ALL);
    row(ClinicPermission.DATA_EXPORT, ALL, ALL, NONE, NONE);
    row(ClinicPermission.RECEPTION_DASHBOARD_VIEW, ALL, ALL, ALL, ALL);
    row(ClinicPermission.MONTHLY_REPORT_VIEW, ALL, ALL, NONE, ALL);
    row(ClinicPermission.AUDIT_VIEW, ALL, NONE, NONE, NONE);
  }

  private static void row(
      ClinicPermission permission,
      AccessScope owner,
      AccessScope manager,
      AccessScope attendant,
      AccessScope viewer) {
    MATRIX.get(OWNER).put(permission, owner);
    MATRIX.get(MANAGER).put(permission, manager);
    MATRIX.get(ATTENDANT).put(permission, attendant);
    MATRIX.get(VIEWER).put(permission, viewer);
  }

  public AccessScope scope(ClinicPermission permission) {
    return MATRIX.get(this).getOrDefault(permission, NONE);
  }

  /** Every permission with its scope, for UI hints. The server still decides each request. */
  public Map<ClinicPermission, AccessScope> permissions() {
    return Collections.unmodifiableMap(MATRIX.get(this));
  }

  /** Roles a MANAGER may assign or manage: never OWNER nor MANAGER (ADR-025). */
  public boolean operational() {
    return this == ATTENDANT || this == VIEWER;
  }
}
