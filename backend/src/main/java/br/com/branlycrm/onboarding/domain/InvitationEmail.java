package br.com.branlycrm.onboarding.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Invited address, normalized like user_accounts.email (trimmed, lower case) and checked with the
 * same shape the database enforces, so an invalid value is a 400 instead of a constraint error.
 */
public record InvitationEmail(String value) {
  private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  public InvitationEmail {
    if (value == null || value.length() > 254 || !SHAPE.matcher(value).matches()) {
      throw new InvalidInputException("email");
    }
  }

  public static InvitationEmail of(String raw) {
    if (raw == null) throw new InvalidInputException("email");
    return new InvitationEmail(raw.strip().toLowerCase(Locale.ROOT));
  }
}
