package br.com.branlycrm.contact.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import java.util.Locale;
import java.util.UUID;

/**
 * Contact list filters. The term matches the name (without accents or case), part of the phone
 * digits, the whole number in any accepted format, or the start of the e-mail; notes are never
 * searched. Terms of two characters are allowed, but cannot use the trigram index.
 */
public record ContactSearch(String term, UUID tagId, boolean includeArchived) {
  public static final int TERM_MIN = 2;
  public static final int TERM_MAX = 100;

  public ContactSearch {
    if (term != null) {
      term = term.strip();
      if (term.isEmpty()) term = null;
    }
    if (term != null && (term.length() < TERM_MIN || term.length() > TERM_MAX)) {
      throw new InvalidInputException("q");
    }
  }

  public static ContactSearch all() {
    return new ContactSearch(null, null, false);
  }

  public String nameTerm() {
    return term == null ? null : SearchText.normalize(term);
  }

  /** The digits of the term, when it has enough of them to look like part of a number. */
  public String phoneDigits() {
    if (term == null) return null;
    String digits = term.replaceAll("[^0-9]", "");
    return digits.length() >= 3 ? digits : null;
  }

  /**
   * The canonical number when the whole term is a phone in any accepted format, so a number typed
   * the old way (without the ninth digit, or with a trunk zero) still finds the contact.
   */
  public String exactPhone() {
    if (term == null) return null;
    try {
      return PhoneNumber.parse(term).e164();
    } catch (InvalidInputException notAPhone) {
      return null;
    }
  }

  public String emailTerm() {
    return term == null ? null : term.toLowerCase(Locale.ROOT);
  }
}
