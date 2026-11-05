package br.com.branlycrm.tenant.domain;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.exceptions.InvalidInputException;

/**
 * Filters of the team list: optional role and active flag, plus pagination. Values are parsed from
 * raw query parameters and validated here, never interpolated into SQL.
 */
public record TeamQuery(ClinicRole role, Boolean active, PageQuery page) {
  public TeamQuery {
    if (page == null) throw new IllegalArgumentException("Page is required");
  }

  public static TeamQuery of(String role, String active, PageQuery page) {
    return new TeamQuery(role(role), active(active), page);
  }

  private static ClinicRole role(String value) {
    if (value == null || value.isEmpty()) return null;
    try {
      return ClinicRole.valueOf(value);
    } catch (IllegalArgumentException unknown) {
      throw new InvalidInputException("role");
    }
  }

  private static Boolean active(String value) {
    if (value == null || value.isEmpty()) return null;
    return switch (value) {
      case "true" -> Boolean.TRUE;
      case "false" -> Boolean.FALSE;
      default -> throw new InvalidInputException("active");
    };
  }
}
