package br.com.branlycrm.tenant;

import static br.com.branlycrm.tenant.domain.AccessScope.ALL;
import static br.com.branlycrm.tenant.domain.AccessScope.NONE;
import static br.com.branlycrm.tenant.domain.AccessScope.OWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.tenant.domain.AccessScope;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.domain.ClinicRole;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

/**
 * The permission matrix of docs/permissoes.md, transcribed by hand and compared row by row with
 * {@link ClinicRole}. The expected table is never derived from production code: a change to either
 * side must break this test until both agree.
 *
 * <p>Transcription conventions: "T", "Editar", "Ver" and "✓" are {@code ALL}; "P" and "Só o próprio
 * desempenho" are {@code OWN}; "—" is {@code NONE}. "Equipe: convidar, mudar papel, desativar" is
 * {@code ALL} for MANAGER because the restriction to operational members lives in TeamPolicy.
 */
class ClinicRoleTest {
  /** Columns in the order of the document: OWNER, MANAGER, RECEPTION, SALES, PROFESSIONAL. */
  private static final List<ClinicRole> COLUMNS =
      List.of(
          ClinicRole.OWNER,
          ClinicRole.MANAGER,
          ClinicRole.RECEPTION,
          ClinicRole.SALES,
          ClinicRole.PROFESSIONAL);

  private static final Map<ClinicPermission, List<AccessScope>> DOCUMENTED =
      new EnumMap<>(ClinicPermission.class);

  static {
    // Dados da clínica, fuso, horários: Editar | Ver | Ver | Ver | Ver
    documented(ClinicPermission.CLINIC_SETTINGS_VIEW, ALL, ALL, ALL, ALL, ALL);
    documented(ClinicPermission.CLINIC_SETTINGS_EDIT, ALL, NONE, NONE, NONE, NONE);
    // Plano e assinatura: Ver | — | — | — | —
    documented(ClinicPermission.SUBSCRIPTION_VIEW, ALL, NONE, NONE, NONE, NONE);
    // Equipe: ver membros: T | T | — | — | —
    documented(ClinicPermission.TEAM_VIEW, ALL, ALL, NONE, NONE, NONE);
    // Equipe: convidar, mudar papel, desativar: T | só operacionais (TeamPolicy) | — | — | —
    documented(ClinicPermission.TEAM_MANAGE, ALL, ALL, NONE, NONE, NONE);
    // Funis, etapas, procedimentos: Editar | Editar | Ver | Ver | —
    documented(ClinicPermission.PIPELINE_VIEW, ALL, ALL, ALL, ALL, NONE);
    documented(ClinicPermission.PIPELINE_EDIT, ALL, ALL, NONE, NONE, NONE);
    // Contatos: criar: ✓ | ✓ | ✓ | ✓ (fica responsável) | —
    documented(ClinicPermission.CONTACT_CREATE, ALL, ALL, ALL, ALL, NONE);
    // Contatos: ver: T | T | T | P | —
    documented(ClinicPermission.CONTACT_READ, ALL, ALL, ALL, OWN, NONE);
    // Contatos: ver dados básicos: T | T | T | P | P
    documented(ClinicPermission.CONTACT_READ_BASIC, ALL, ALL, ALL, OWN, OWN);
    // Contatos: editar: T | T | T | P | —
    documented(ClinicPermission.CONTACT_EDIT, ALL, ALL, ALL, OWN, NONE);
    // Contatos: arquivar: T | T | T | — | —
    documented(ClinicPermission.CONTACT_ARCHIVE, ALL, ALL, ALL, NONE, NONE);
    // Oportunidades: criar: ✓ | ✓ | ✓ | ✓ | —
    documented(ClinicPermission.OPPORTUNITY_CREATE, ALL, ALL, ALL, ALL, NONE);
    // Oportunidades: escolher responsável ao criar: T | T | T | — | —
    documented(ClinicPermission.OPPORTUNITY_ASSIGN_ON_CREATE, ALL, ALL, ALL, NONE, NONE);
    // Oportunidades: ver: T | T | T | P | —
    documented(ClinicPermission.OPPORTUNITY_READ, ALL, ALL, ALL, OWN, NONE);
    // Oportunidades: mover etapa e editar valor: T | T | — | P | —
    documented(ClinicPermission.OPPORTUNITY_UPDATE, ALL, ALL, NONE, OWN, NONE);
    // Oportunidades: ganhar ou perder: T | T | — | P | —
    documented(ClinicPermission.OPPORTUNITY_CLOSE, ALL, ALL, NONE, OWN, NONE);
    // Oportunidades: reatribuir responsável: T | T | — | — | —
    documented(ClinicPermission.OPPORTUNITY_REASSIGN, ALL, ALL, NONE, NONE, NONE);
    // Atividades e notas: T | T | T | P | —
    documented(ClinicPermission.ACTIVITY_WRITE, ALL, ALL, ALL, OWN, NONE);
    // Tarefas: criar para si: ✓ | ✓ | ✓ | ✓ | ✓
    documented(ClinicPermission.TASK_CREATE_OWN, ALL, ALL, ALL, ALL, ALL);
    // Tarefas: atribuir a outros: ✓ | ✓ | ✓ | — | —
    documented(ClinicPermission.TASK_ASSIGN, ALL, ALL, ALL, NONE, NONE);
    // Tarefas: ver: T | T | P | P | P
    documented(ClinicPermission.TASK_READ, ALL, ALL, OWN, OWN, OWN);
    // Agenda: criar, confirmar, reagendar, cancelar: T | T | T | P | —
    documented(ClinicPermission.APPOINTMENT_MANAGE, ALL, ALL, ALL, OWN, NONE);
    // Agenda: ver: T | T | T | P | P
    documented(ClinicPermission.APPOINTMENT_READ, ALL, ALL, ALL, OWN, OWN);
    // Agenda: registrar comparecimento ou falta: T | T | T | — | P
    documented(ClinicPermission.APPOINTMENT_ATTENDANCE, ALL, ALL, ALL, NONE, OWN);
    // Dashboard e relatórios: T | T | — | só o próprio desempenho | —
    documented(ClinicPermission.REPORTS_VIEW, ALL, ALL, NONE, OWN, NONE);
    // Exportação de dados: ✓ | — | — | — | —
    documented(ClinicPermission.DATA_EXPORT, ALL, NONE, NONE, NONE, NONE);
    // Auditoria da clínica: Ver | — | — | — | —
    documented(ClinicPermission.AUDIT_VIEW, ALL, NONE, NONE, NONE, NONE);
  }

  private static void documented(ClinicPermission permission, AccessScope... byColumn) {
    if (DOCUMENTED.put(permission, List.of(byColumn)) != null || byColumn.length != 5) {
      throw new IllegalStateException("Malformed expected row " + permission);
    }
  }

  @Test
  void everyPermissionHasADocumentedRow() {
    assertThat(DOCUMENTED.keySet()).containsExactlyInAnyOrder(ClinicPermission.values());
  }

  @Test
  void everyRoleIsADocumentedColumn() {
    assertThat(COLUMNS).containsExactlyInAnyOrder(ClinicRole.values());
  }

  @Test
  void scopeOfEveryRoleAndPermissionMatchesTheDocument() {
    SoftAssertions softly = new SoftAssertions();
    for (var row : DOCUMENTED.entrySet()) {
      for (int column = 0; column < COLUMNS.size(); column++) {
        ClinicRole role = COLUMNS.get(column);
        softly
            .assertThat(role.scope(row.getKey()))
            .as("%s × %s", row.getKey(), role)
            .isEqualTo(row.getValue().get(column));
      }
    }
    softly.assertAll();
  }

  @Test
  void permissionsMapListsEveryPermissionExplicitlyWithTheSameScope() {
    // scope() falls back to NONE; the map must not rely on that fallback for a missing row.
    for (ClinicRole role : ClinicRole.values()) {
      Map<ClinicPermission, AccessScope> permissions = role.permissions();
      assertThat(permissions.keySet())
          .as("rows of %s", role)
          .containsExactlyInAnyOrder(ClinicPermission.values());
      for (ClinicPermission permission : ClinicPermission.values()) {
        assertThat(permissions.get(permission))
            .as("%s × %s", permission, role)
            .isEqualTo(role.scope(permission));
      }
    }
  }

  @Test
  void permissionsMapIsReadOnly() {
    var permissions = ClinicRole.SALES.permissions();
    assertThatThrownBy(() -> permissions.put(ClinicPermission.DATA_EXPORT, ALL))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(ClinicRole.SALES.scope(ClinicPermission.DATA_EXPORT)).isEqualTo(NONE);
  }

  @Test
  void onlyReceptionSalesAndProfessionalAreOperational() {
    EnumSet<ClinicRole> operational = EnumSet.noneOf(ClinicRole.class);
    for (ClinicRole role : ClinicRole.values()) if (role.operational()) operational.add(role);
    assertThat(operational)
        .containsExactlyInAnyOrder(ClinicRole.RECEPTION, ClinicRole.SALES, ClinicRole.PROFESSIONAL);
  }

  @Test
  void ownerReachesEverythingAndExclusiveRowsBelongOnlyToOwner() {
    for (ClinicPermission permission : ClinicPermission.values()) {
      assertThat(ClinicRole.OWNER.scope(permission)).as("OWNER × %s", permission).isEqualTo(ALL);
    }
    for (ClinicPermission exclusive :
        List.of(
            ClinicPermission.CLINIC_SETTINGS_EDIT,
            ClinicPermission.SUBSCRIPTION_VIEW,
            ClinicPermission.DATA_EXPORT,
            ClinicPermission.AUDIT_VIEW)) {
      for (ClinicRole role : ClinicRole.values()) {
        if (role != ClinicRole.OWNER) {
          assertThat(role.scope(exclusive)).as("%s × %s", exclusive, role).isEqualTo(NONE);
        }
      }
    }
  }
}
