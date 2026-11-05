package br.com.branlycrm.tenant.domain;

/**
 * Who may change whose role or deactivate whom (docs/permissoes.md). The last-owner invariant is
 * not decided here: it depends on the other memberships and is checked under a lock, with a
 * database trigger as backstop.
 */
public final class TeamPolicy {
  private TeamPolicy() {}

  /**
   * @param newRole the role being assigned, or {@code null} when deactivating
   */
  public static boolean mayManage(ClinicAccess actor, TeamMember target, ClinicRole newRole) {
    if (!actor.allows(ClinicPermission.TEAM_MANAGE)) return false;
    if (actor.subjectId().equals(target.subjectId())) return false; // nobody changes themselves
    return switch (actor.role()) {
      case OWNER -> true;
      case MANAGER -> target.role().operational() && (newRole == null || newRole.operational());
      default -> false;
    };
  }
}
