package br.com.branlycrm.contact.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Accent- and case-insensitive form of a name, computed by the application so the stored column and
 * the searched term always follow the same rules (no database {@code unaccent}).
 */
public final class SearchText {
  private static final Pattern MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern SPACES = Pattern.compile("\\s+");

  /** Escape character of the LIKE patterns built here; use it in {@code LIKE ... ESCAPE '!'}. */
  public static final char ESCAPE = '!';

  private SearchText() {}

  public static String normalize(String text) {
    String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
    String plain = MARKS.matcher(decomposed).replaceAll("");
    return SPACES.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
  }

  /** A LIKE pattern matching {@code term} anywhere, with its wildcards taken literally. */
  public static String contains(String term) {
    return "%" + escapeLike(term) + "%";
  }

  /** A LIKE pattern matching values that start with {@code term}, wildcards taken literally. */
  public static String startsWith(String term) {
    return escapeLike(term) + "%";
  }

  private static String escapeLike(String term) {
    return term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }
}
