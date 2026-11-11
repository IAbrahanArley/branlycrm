package br.com.branlycrm.pipeline.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;

/** Names of stages (40) and loss reasons (60): stripped, one line, no invisible characters. */
public final class ConfigName {
  public static final int STAGE_MAX = 40;
  public static final int LOSS_REASON_MAX = 60;

  private ConfigName() {}

  public static String stage(String value) {
    return of(value, STAGE_MAX);
  }

  public static String lossReason(String value) {
    return of(value, LOSS_REASON_MAX);
  }

  private static String of(String value, int max) {
    String text = value == null ? null : value.strip();
    if (text == null
        || text.isEmpty()
        || text.length() > max
        || text.codePoints().anyMatch(ConfigName::forbidden)) {
      throw new InvalidInputException("name");
    }
    return text;
  }

  private static boolean forbidden(int c) {
    int kind = Character.getType(c);
    return Character.isISOControl(c)
        || kind == Character.FORMAT
        || kind == Character.LINE_SEPARATOR
        || kind == Character.PARAGRAPH_SEPARATOR;
  }
}
