package br.com.branlycrm.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicRole;
import br.com.branlycrm.tenant.domain.TeamMember;
import br.com.branlycrm.tenant.domain.TeamPolicy;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

/** Who may change whose role or deactivate whom (docs/permissoes.md, "Regras invariantes"). */
class TeamPolicyTest {
  private static final Set<ClinicRole> OPERATIONAL =
      EnumSet.of(ClinicRole.ATTENDANT, ClinicRole.VIEWER);
  private static final UUID TENANT = UUID.randomUUID();

  /** Every new role, plus {@code null} for deactivation. */
  private static List<ClinicRole> newRoles() {
    List<ClinicRole> roles = new ArrayList<>(Arrays.asList(ClinicRole.values()));
    roles.add(null);
    return roles;
  }

  @Test
  void ownerManagesEveryOtherMemberWithAnyRole() {
    ClinicAccess owner = actor(ClinicRole.OWNER);
    for (ClinicRole target : ClinicRole.values()) {
      for (ClinicRole newRole : newRoles()) {
        assertThat(TeamPolicy.mayManage(owner, member(UUID.randomUUID(), target), newRole))
            .as("OWNER on %s -> %s", target, newRole)
            .isTrue();
      }
    }
  }

  @Test
  void managerReachesOnlyOperationalTargetsAndOperationalNewRoles() {
    ClinicAccess manager = actor(ClinicRole.MANAGER);
    SoftAssertions softly = new SoftAssertions();
    for (ClinicRole target : ClinicRole.values()) {
      for (ClinicRole newRole : newRoles()) {
        boolean expected =
            OPERATIONAL.contains(target) && (newRole == null || OPERATIONAL.contains(newRole));
        softly
            .assertThat(TeamPolicy.mayManage(manager, member(UUID.randomUUID(), target), newRole))
            .as("MANAGER on %s -> %s", target, newRole)
            .isEqualTo(expected);
      }
    }
    softly.assertAll();
  }

  @Test
  void managerMayDeactivateOperationalMembersButNotOwnersOrManagers() {
    ClinicAccess manager = actor(ClinicRole.MANAGER);
    assertThat(TeamPolicy.mayManage(manager, member(UUID.randomUUID(), ClinicRole.ATTENDANT), null))
        .isTrue();
    assertThat(TeamPolicy.mayManage(manager, member(UUID.randomUUID(), ClinicRole.OWNER), null))
        .isFalse();
    assertThat(TeamPolicy.mayManage(manager, member(UUID.randomUUID(), ClinicRole.MANAGER), null))
        .isFalse();
  }

  @Test
  void operationalRolesNeverManageAnyone() {
    for (ClinicRole actorRole : OPERATIONAL) {
      ClinicAccess actor = actor(actorRole);
      for (ClinicRole target : ClinicRole.values()) {
        for (ClinicRole newRole : newRoles()) {
          assertThat(TeamPolicy.mayManage(actor, member(UUID.randomUUID(), target), newRole))
              .as("%s on %s -> %s", actorRole, target, newRole)
              .isFalse();
        }
      }
    }
  }

  @Test
  void nobodyChangesOrDeactivatesThemselves() {
    for (ClinicRole actorRole : ClinicRole.values()) {
      ClinicAccess actor = actor(actorRole);
      TeamMember self = member(actor.subjectId(), actorRole);
      for (ClinicRole newRole : newRoles()) {
        assertThat(TeamPolicy.mayManage(actor, self, newRole))
            .as("%s on self -> %s", actorRole, newRole)
            .isFalse();
      }
    }
  }

  private static ClinicAccess actor(ClinicRole role) {
    String suffix = TENANT.toString().replace("-", "");
    TenantDatabase database =
        new TenantDatabase(
            TENANT,
            "clinic_" + suffix,
            "tenant_" + suffix,
            "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
    return new ClinicAccess(database, UUID.randomUUID(), role);
  }

  private static TeamMember member(UUID subject, ClinicRole role) {
    return new TeamMember(subject, "Synthetic member", "member@example.test", role, true);
  }
}
