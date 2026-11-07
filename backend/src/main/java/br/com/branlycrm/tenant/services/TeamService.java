package br.com.branlycrm.tenant.services;

import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.domain.ClinicRole;
import br.com.branlycrm.tenant.domain.TeamMember;
import br.com.branlycrm.tenant.domain.TeamPolicy;
import br.com.branlycrm.tenant.domain.TeamQuery;
import br.com.branlycrm.tenant.dto.response.TeamMemberView;
import br.com.branlycrm.tenant.exceptions.TeamException;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TeamRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team management of the selected clinic (ADR-025, docs/permissoes.md). Every mutation locks the
 * clinic, re-reads the actor's role under lock and checks the last-owner invariant before writing;
 * the V6 trigger is the backstop. Changes take effect on the next request because roles and
 * memberships are revalidated on every request.
 */
@Service
public class TeamService {
  private final TenantAccessService access;
  private final TeamRepository team;

  public TeamService(TenantAccessService access, TeamRepository team) {
    this.access = access;
    this.team = team;
  }

  /** One snapshot for count and items, so total matches the page under concurrent changes. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PageView<TeamMemberView> list(UUID tenantId, UUID actor, TeamQuery query) {
    access.require(tenantId, actor, ClinicPermission.TEAM_VIEW);
    PageResult<TeamMember> result = team.list(tenantId, query);
    return new PageView<>(
        result.items().stream().map(TeamService::view).toList(),
        query.page().page(),
        query.page().pageSize(),
        result.total());
  }

  @Transactional
  public TeamMemberView changeRole(UUID tenantId, UUID actor, UUID target, ClinicRole newRole) {
    if (newRole == null) throw new IllegalArgumentException("Role is required");
    TeamMember member = authorizeChange(tenantId, actor, target, newRole);
    if (member.role() == newRole) return view(member);
    if (member.role() == ClinicRole.OWNER) requireAnotherOwner(tenantId, target);
    team.changeRole(tenantId, target, newRole);
    return view(
        new TeamMember(
            member.subjectId(), member.displayName(), member.email(), newRole, member.active()));
  }

  @Transactional
  public void deactivate(UUID tenantId, UUID actor, UUID target) {
    TeamMember member = authorizeChange(tenantId, actor, target, null);
    if (member.role() == ClinicRole.OWNER) requireAnotherOwner(tenantId, target);
    team.deactivate(tenantId, target);
  }

  private TeamMember authorizeChange(
      UUID tenantId, UUID actorId, UUID targetId, ClinicRole newRole) {
    // Authorizes the session first, then serializes and re-reads the actor's role under lock.
    ClinicAccess authorized = access.require(tenantId, actorId, ClinicPermission.TEAM_MANAGE);
    if (!team.lockActiveClinic(tenantId)) throw new TenantAccessException();
    ClinicRole role = team.lockActorRole(tenantId, actorId).orElseThrow(TenantAccessException::new);
    ClinicAccess actor = new ClinicAccess(authorized.database(), actorId, role);
    if (!actor.allows(ClinicPermission.TEAM_MANAGE)) throw new TenantAccessException();
    TeamMember member =
        team.lockActiveMember(tenantId, targetId)
            .orElseThrow(
                () -> new TeamException(TeamException.Reason.NOT_FOUND, "Membro não encontrado."));
    if (!TeamPolicy.mayManage(actor, member, newRole)) {
      throw new TeamException(
          TeamException.Reason.FORBIDDEN, "Seu papel não permite alterar este membro.");
    }
    return member;
  }

  private void requireAnotherOwner(UUID tenantId, UUID owner) {
    if (!team.hasOtherActiveOwner(tenantId, owner)) {
      throw new TeamException(
          TeamException.Reason.LAST_OWNER, "A clínica precisa de ao menos um proprietário.");
    }
  }

  private static TeamMemberView view(TeamMember member) {
    return new TeamMemberView(
        member.subjectId(),
        member.displayName(),
        member.email(),
        member.role().name(),
        member.active());
  }
}
