package br.com.branlycrm.contact.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

/**
 * Editable data of a contact (PAC-01), validated once for creation and edition. Notes are free text
 * that may receive health data: they are capped, never indexed nor searched (ADR-032).
 */
public record ContactFields(
    String name, PhoneNumber phone, String email, LocalDate birthDate, String notes) {
  public static final int NAME_MAX = 150;
  public static final int EMAIL_MAX = 254;
  public static final int NOTES_MAX = 2000;
  private static final LocalDate OLDEST_BIRTH = LocalDate.of(1900, 1, 1);

  /** Control, format (bidi, zero-width) and line-separator characters, ASCII or not. */
  static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

  /** Stricter than the database check, so any Unicode space is refused here first. */
  private static final Pattern EMAIL =
      Pattern.compile("[^@\\s\\p{Z}\\p{Cc}\\p{Cf}]+@[^@\\s\\p{Z}\\p{Cc}\\p{Cf}]+");

  /** Notes keep line breaks and tabs; other control characters (NUL included) are refused. */
  private static final Pattern NOTES_CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\r\\t]]");

  public ContactFields {
    name = name == null ? null : name.strip();
    if (name == null || name.isEmpty() || name.length() > NAME_MAX) {
      throw new InvalidInputException("name");
    }
    // The search form must fit its column too: decomposition can lengthen some scripts.
    if (INVISIBLE.matcher(name).find() || SearchText.normalize(name).length() > NAME_MAX) {
      throw new InvalidInputException("name");
    }
    if (phone == null) throw new InvalidInputException("phone");
    email = blankToNull(email);
    if (email != null && (email.length() > EMAIL_MAX || !EMAIL.matcher(email).matches())) {
      throw new InvalidInputException("email");
    }
    // A day of tolerance: the clinic's date may be ahead of UTC.
    if (birthDate != null
        && (birthDate.isBefore(OLDEST_BIRTH)
            || birthDate.isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1)))) {
      throw new InvalidInputException("birthDate");
    }
    notes = blankToNull(notes);
    if (notes != null && (notes.length() > NOTES_MAX || NOTES_CONTROL.matcher(notes).find())) {
      throw new InvalidInputException("notes");
    }
  }

  public String nameSearch() {
    return SearchText.normalize(name);
  }

  private static String blankToNull(String value) {
    if (value == null) return null;
    String stripped = value.strip();
    return stripped.isEmpty() ? null : stripped;
  }

  /** Keeps personal data out of logs and exception messages. */
  @Override
  public String toString() {
    return "ContactFields[***]";
  }
}
