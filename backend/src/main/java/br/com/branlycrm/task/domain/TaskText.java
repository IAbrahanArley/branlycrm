package br.com.branlycrm.task.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;

/** Text rules of tasks: stripped, capped, and without control or invisible characters. */
public final class TaskText {
  private TaskText() {}

  /** One line; null when blank. */
  public static String line(String value, int max, String field) {
    return text(value, max, field, false);
  }

  /** Line breaks and tabs allowed; null when blank. */
  public static String multiline(String value, int max, String field) {
    return text(value, max, field, true);
  }

  private static String text(String value, int max, String field, boolean breaks) {
    String text = value == null ? null : value.strip();
    if (text == null || text.isEmpty()) return null;
    if (text.length() > max || text.codePoints().anyMatch(c -> forbidden(c, breaks))) {
      throw new InvalidInputException(field);
    }
    return text;
  }

  private static boolean forbidden(int c, boolean breaks) {
    if (breaks && (c == '\n' || c == '\r' || c == '\t')) return false;
    int kind = Character.getType(c);
    return Character.isISOControl(c)
        || kind == Character.FORMAT
        || kind == Character.LINE_SEPARATOR
        || kind == Character.PARAGRAPH_SEPARATOR;
  }
}
