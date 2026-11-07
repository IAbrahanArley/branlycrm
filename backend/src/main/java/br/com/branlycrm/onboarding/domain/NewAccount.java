package br.com.branlycrm.onboarding.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import java.nio.charset.StandardCharsets;

/**
 * Name and password chosen when accepting an invitation. The password has at least 12 characters
 * and at most 72 bytes in UTF-8, the bcrypt limit (Spring refuses longer ones instead of silently
 * truncating). {@link #toString()} never shows the password.
 */
public record NewAccount(String displayName, String password) {
  public static final int MIN_PASSWORD_CHARACTERS = 12;
  public static final int MAX_PASSWORD_BYTES = 72;

  public NewAccount {
    if (displayName == null) throw new InvalidInputException("displayName");
    displayName = displayName.strip();
    if (displayName.isEmpty() || displayName.length() > 160) {
      throw new InvalidInputException("displayName");
    }
    if (password == null
        || password.codePointCount(0, password.length()) < MIN_PASSWORD_CHARACTERS
        || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
      throw new InvalidInputException("password");
    }
  }

  @Override
  public String toString() {
    return "NewAccount[displayName=" + displayName + "]";
  }
}
