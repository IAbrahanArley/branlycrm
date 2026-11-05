package br.com.branlycrm.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.tenant.domain.ClinicRole;
import br.com.branlycrm.tenant.domain.TeamQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Team list filters parsed from raw query parameters (GET /v1/clinic/team). */
class TeamQueryTest {
  private static final PageQuery FIRST = new PageQuery(1, 25);

  @ParameterizedTest
  @EnumSource(ClinicRole.class)
  void acceptsEveryRoleByItsExactName(ClinicRole role) {
    assertThat(TeamQuery.of(role.name(), null, FIRST).role()).isEqualTo(role);
  }

  @Test
  void acceptsBothActiveFlags() {
    assertThat(TeamQuery.of(null, "true", FIRST).active()).isTrue();
    assertThat(TeamQuery.of(null, "false", FIRST).active()).isFalse();
    TeamQuery combined = TeamQuery.of("SALES", "false", FIRST);
    assertThat(combined.role()).isEqualTo(ClinicRole.SALES);
    assertThat(combined.active()).isFalse();
    assertThat(combined.page()).isSameAs(FIRST);
  }

  @ParameterizedTest
  @NullAndEmptySource
  void absentFiltersMatchEverything(String absent) {
    TeamQuery query = TeamQuery.of(absent, absent, FIRST);
    assertThat(query.role()).isNull();
    assertThat(query.active()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"ADMIN", "owner", "Owner", " OWNER", "OWNER ", "OWNER,SALES", "1", "*"})
  void unknownRolesReportRole(String role) {
    assertInvalid(() -> TeamQuery.of(role, null, FIRST), "role");
  }

  @ParameterizedTest
  @ValueSource(strings = {"TRUE", "False", "yes", "1", "0", " true", "true,false", "null"})
  void otherActiveValuesReportActive(String active) {
    assertInvalid(() -> TeamQuery.of(null, active, FIRST), "active");
  }

  @Test
  void roleIsCheckedBeforeActive() {
    assertInvalid(() -> TeamQuery.of("ADMIN", "yes", FIRST), "role");
  }

  @Test
  void pageIsRequired() {
    assertThatThrownBy(() -> TeamQuery.of(null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .isNotInstanceOf(InvalidInputException.class);
  }

  private static void assertInvalid(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            InvalidInputException.class,
            invalid -> assertThat(invalid.fields()).containsExactly(field));
  }
}
