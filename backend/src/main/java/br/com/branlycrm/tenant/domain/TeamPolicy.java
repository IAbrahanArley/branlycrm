package br.com.branlycrm.tenant.domain;

/**
 * Who may change whose role or deactivate whom (docs/permissoes.md). The last-owner invariant is
 * not decided here: it depends on the other memberships and is checked under a lock, with a
 * database trigger as backstop.
 */
public final class TeamPolicy {
  private TeamPolicy() {}

  /**
   * Who may invite someone with {@code role}, or rewrite and revoke a pending invitation for it:
   * the same reach as changing a member to that role.
   */
  public static boolean mayInvite(ClinicRole actorRole, ClinicRole role) {
    if (actorRole == null || role == null) return false;
    if (actorRole.scope(ClinicPermission.TEAM_MANAGE) == AccessScope.NONE) return false;
    return switch (actorRole) {
      case OWNER -> true;
      case MANAGER -> role.operational();
      default -> false;
    };
  }

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
