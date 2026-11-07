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
 * The permission matrix of docs/permissoes.md (ADR-025), transcribed by hand and compared row by
 * row with {@link ClinicRole}. The expected table is never derived from production code: a change
 * to either side must break this test until both agree.
 *
 * <p>Transcription conventions: "✓" is {@code ALL}; "Si" is {@code OWN}; "—" is {@code NONE}.
 * "Equipe: convidar, mudar papel, desativar" is {@code ALL} for MANAGER because the restriction to
 * ATTENDANT and VIEWER lives in TeamPolicy.
 */
class ClinicRoleTest {
  /** Columns in the order of the document: OWNER, MANAGER, ATTENDANT, VIEWER. */
  private static final List<ClinicRole> COLUMNS =
      List.of(ClinicRole.OWNER, ClinicRole.MANAGER, ClinicRole.ATTENDANT, ClinicRole.VIEWER);

  private static final Map<ClinicPermission, List<AccessScope>> DOCUMENTED =
      new EnumMap<>(ClinicPermission.class);

  static {
    // Dados da clínica e fuso: editar: ✓ | — | — | —
    documented(ClinicPermission.CLINIC_SETTINGS_EDIT, ALL, NONE, NONE, NONE);
    // Dados da clínica e fuso: ver: ✓ | ✓ | ✓ | ✓
    documented(ClinicPermission.CLINIC_SETTINGS_VIEW, ALL, ALL, ALL, ALL);
    // Plano e assinatura: ✓ | — | — | —
    documented(ClinicPermission.SUBSCRIPTION_VIEW, ALL, NONE, NONE, NONE);
    // Equipe: ver: ✓ | ✓ | — | —
    documented(ClinicPermission.TEAM_VIEW, ALL, ALL, NONE, NONE);
    // Equipe: convidar, mudar papel, desativar: ✓ | só ATTENDANT e VIEWER (TeamPolicy) | — | —
    documented(ClinicPermission.TEAM_MANAGE, ALL, ALL, NONE, NONE);
    // Configuração comercial: ✓ | ✓ | — | —
    documented(ClinicPermission.COMMERCIAL_SETTINGS_EDIT, ALL, ALL, NONE, NONE);
    // Canal WhatsApp: ver estado: ✓ | ✓ | — | —
    documented(ClinicPermission.CHANNEL_VIEW, ALL, ALL, NONE, NONE);
    // Contatos: ver e buscar: ✓ | ✓ | ✓ | ✓
    documented(ClinicPermission.CONTACT_READ, ALL, ALL, ALL, ALL);
    // Contatos: criar, editar, tags, consentimento: ✓ | ✓ | ✓ | —
    documented(ClinicPermission.CONTACT_WRITE, ALL, ALL, ALL, NONE);
    // Contatos: arquivar: ✓ | ✓ | — | —
    documented(ClinicPermission.CONTACT_ARCHIVE, ALL, ALL, NONE, NONE);
    // Contatos: importar planilha: ✓ | ✓ | — | —
    documented(ClinicPermission.CONTACT_IMPORT, ALL, ALL, NONE, NONE);
    // Leads: ver, Kanban e linha do tempo: ✓ | ✓ | ✓ | ✓
    documented(ClinicPermission.LEAD_READ, ALL, ALL, ALL, ALL);
    // Leads: criar, mover etapa, temperatura, notas: ✓ | ✓ | ✓ | —
    documented(ClinicPermission.LEAD_WRITE, ALL, ALL, ALL, NONE);
    // Leads: assumir como responsável: ✓ | ✓ | Si | —
    documented(ClinicPermission.LEAD_TAKE, ALL, ALL, OWN, NONE);
    // Leads: atribuir ou reatribuir a outro: ✓ | ✓ | — | —
    documented(ClinicPermission.LEAD_ASSIGN, ALL, ALL, NONE, NONE);
    // Leads: corrigir origem manualmente: ✓ | ✓ | ✓ | —
    documented(ClinicPermission.LEAD_ORIGIN_EDIT, ALL, ALL, ALL, NONE);
    // Vendas e perdas: registrar: ✓ | ✓ | ✓ | —
    documented(ClinicPermission.SALE_REGISTER, ALL, ALL, ALL, NONE);
    // Vendas: corrigir ou cancelar registro: ✓ | ✓ | — | —
    documented(ClinicPermission.SALE_CORRECT, ALL, ALL, NONE, NONE);
    // Caixa de entrada: ler conversas: ✓ | ✓ | ✓ | ✓
    documented(ClinicPermission.INBOX_READ, ALL, ALL, ALL, ALL);
    // Caixa de entrada: responder, assumir, enviar mídia e template: ✓ | ✓ | ✓ | —
    documented(ClinicPermission.INBOX_REPLY, ALL, ALL, ALL, NONE);
    // Tarefas: criar e atribuir: ✓ | ✓ | ✓ | —
    documented(ClinicPermission.TASK_WRITE, ALL, ALL, ALL, NONE);
    // Tarefas: ver: ✓ | ✓ | ✓ | ✓
    documented(ClinicPermission.TASK_READ, ALL, ALL, ALL, ALL);
    // Notificações próprias: ✓ | ✓ | ✓ | ✓
    documented(ClinicPermission.NOTIFICATIONS_VIEW, ALL, ALL, ALL, ALL);
    // Lista de leads com filtros: ✓ | ✓ | ✓ | ✓
    documented(ClinicPermission.LEAD_LIST_VIEW, ALL, ALL, ALL, ALL);
    // Lista de leads: exportar: ✓ | ✓ | — | —
    documented(ClinicPermission.DATA_EXPORT, ALL, ALL, NONE, NONE);
    // Dashboard da recepção: ✓ | ✓ | ✓ | ✓
    documented(ClinicPermission.RECEPTION_DASHBOARD_VIEW, ALL, ALL, ALL, ALL);
    // Relatório mensal: ✓ | ✓ | — | ✓
    documented(ClinicPermission.MONTHLY_REPORT_VIEW, ALL, ALL, NONE, ALL);
    // Auditoria da clínica: ✓ | — | — | —
    documented(ClinicPermission.AUDIT_VIEW, ALL, NONE, NONE, NONE);
  }

  private static void documented(ClinicPermission permission, AccessScope... byColumn) {
    if (DOCUMENTED.put(permission, List.of(byColumn)) != null || byColumn.length != 4) {
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
    var permissions = ClinicRole.VIEWER.permissions();
    assertThatThrownBy(() -> permissions.put(ClinicPermission.DATA_EXPORT, ALL))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(ClinicRole.VIEWER.scope(ClinicPermission.DATA_EXPORT)).isEqualTo(NONE);
  }

  @Test
  void onlyAttendantAndViewerAreOperational() {
    EnumSet<ClinicRole> operational = EnumSet.noneOf(ClinicRole.class);
    for (ClinicRole role : ClinicRole.values()) if (role.operational()) operational.add(role);
    assertThat(operational).containsExactlyInAnyOrder(ClinicRole.ATTENDANT, ClinicRole.VIEWER);
  }

  @Test
  void viewerNeverChangesAnything() {
    for (ClinicPermission write :
        List.of(
            ClinicPermission.CONTACT_WRITE,
            ClinicPermission.LEAD_WRITE,
            ClinicPermission.LEAD_TAKE,
            ClinicPermission.SALE_REGISTER,
            ClinicPermission.INBOX_REPLY,
            ClinicPermission.TASK_WRITE,
            ClinicPermission.DATA_EXPORT)) {
      assertThat(ClinicRole.VIEWER.scope(write)).as("VIEWER × %s", write).isEqualTo(NONE);
    }
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
            ClinicPermission.AUDIT_VIEW)) {
      for (ClinicRole role : ClinicRole.values()) {
        if (role != ClinicRole.OWNER) {
          assertThat(role.scope(exclusive)).as("%s × %s", exclusive, role).isEqualTo(NONE);
        }
      }
    }
  }
}
