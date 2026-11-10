package br.com.branlycrm.catalog.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import java.util.regex.Pattern;

/** Text rules shared by the catalog records. */
final class CatalogText {
  /** Control, format (bidi, zero-width) and line-separator characters, ASCII or not. */
  private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

  private CatalogText() {}

  /** Stripped, 1..max characters, without invisible characters. */
  static String required(String value, int max, String field) {
    String text = optional(value, max, field);
    if (text == null) throw new InvalidInputException(field);
    return text;
  }

  /** Null when blank; otherwise as {@link #required}. */
  static String optional(String value, int max, String field) {
    String text = value == null ? null : value.strip();
    if (text == null || text.isEmpty()) return null;
    if (text.length() > max || INVISIBLE.matcher(text).find()) {
      throw new InvalidInputException(field);
    }
    return text;
  }
}
