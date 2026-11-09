package br.com.branlycrm.contact.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import java.util.regex.Pattern;

/**
 * Canonical E.164 phone number of a contact, the key that prevents duplicates within a clinic
 * (PAC-03). Manual entry, import and the WhatsApp webhook all go through {@link #parse}, so the
 * same number always becomes the same value (ADR-032).
 *
 * <p>Brazilian numbers (the default region) follow the national plan: two-digit area code without
 * zero, then a nine-digit mobile starting with 9 or an eight-digit landline starting with 2 to 5.
 * An eight-digit number starting with 6 to 9 is a mobile written before the ninth digit, and gets
 * the 9 inserted: WhatsApp may still report such numbers without it. Other countries are checked
 * only for the generic E.164 shape.
 */
public record PhoneNumber(String e164) {
  private static final Pattern CANONICAL = Pattern.compile("\\+[1-9][0-9]{7,14}");
  private static final Pattern BRAZIL = Pattern.compile("\\+55[1-9]{2}(9[0-9]{8}|[2-5][0-9]{7})");
  private static final Pattern ALLOWED = Pattern.compile("[0-9 +().\\-]{1,32}");

  public PhoneNumber {
    if (e164 == null
        || !CANONICAL.matcher(e164).matches()
        || (e164.startsWith("+55") && !BRAZIL.matcher(e164).matches())) {
      throw new InvalidInputException("phone");
    }
  }

  /** Normalizes a number typed, imported or received in any common format. */
  public static PhoneNumber parse(String raw) {
    if (raw == null) throw new InvalidInputException("phone");
    String trimmed = raw.strip();
    if (!ALLOWED.matcher(trimmed).matches() || trimmed.indexOf('+', 1) >= 0) {
      throw new InvalidInputException("phone");
    }
    String digits = trimmed.replaceAll("[^0-9]", "");
    if (trimmed.startsWith("+")) return international(digits);
    if (digits.startsWith("00")) return international(digits.substring(2));
    if (digits.startsWith("0")) return brazil(digits.substring(1));
    if (digits.length() == 10 || digits.length() == 11) return brazil(digits);
    if ((digits.length() == 12 || digits.length() == 13) && digits.startsWith("55")) {
      return brazil(digits.substring(2));
    }
    throw new InvalidInputException("phone");
  }

  private static PhoneNumber international(String digits) {
    if (digits.startsWith("55")) return brazil(digits.substring(2));
    return new PhoneNumber("+" + digits);
  }

  /** National significant number: area code plus subscriber, without trunk or carrier prefix. */
  private static PhoneNumber brazil(String national) {
    if (national.length() != 10 && national.length() != 11) {
      throw new InvalidInputException("phone");
    }
    String area = national.substring(0, 2);
    String subscriber = national.substring(2);
    if (subscriber.length() == 8 && subscriber.charAt(0) >= '6') subscriber = "9" + subscriber;
    return new PhoneNumber("+55" + area + subscriber);
  }

  /** Never prints the number. */
  @Override
  public String toString() {
    return "PhoneNumber[***]";
  }
}
