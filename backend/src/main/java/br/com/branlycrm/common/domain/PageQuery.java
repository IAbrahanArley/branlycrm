package br.com.branlycrm.common.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;

/**
 * Validated pagination parameters (docs/api-convencoes.md): {@code page} starts at 1, {@code
 * pageSize} defaults to 25 and is capped at 100 so no request scans an unbounded list.
 */
public record PageQuery(int page, int pageSize) {
  public static final int DEFAULT_SIZE = 25;
  public static final int MAX_SIZE = 100;

  /** Upper bound keeps the offset inside an int and away from pathological scans. */
  public static final int MAX_PAGE = 10_000;

  public PageQuery {
    if (page < 1 || page > MAX_PAGE) throw new InvalidInputException("page");
    if (pageSize < 1 || pageSize > MAX_SIZE) throw new InvalidInputException("pageSize");
  }

  /** Parses raw query parameters; absent values use the defaults. */
  public static PageQuery of(String page, String pageSize) {
    return new PageQuery(parse(page, 1, "page"), parse(pageSize, DEFAULT_SIZE, "pageSize"));
  }

  public int offset() {
    return (page - 1) * pageSize;
  }

  private static int parse(String value, int fallback, String field) {
    if (value == null || value.isEmpty()) return fallback;
    if (!value.matches("[0-9]{1,6}")) throw new InvalidInputException(field);
    return Integer.parseInt(value);
  }
}
