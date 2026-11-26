package br.com.branlycrm.whatsapp.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A WhatsApp message template as Meta defines it (CRM-085, ADR-036), with the rules of the MVP:
 * supported means a text header (or none), a body, an optional footer and buttons without
 * parameters, outside the AUTHENTICATION category. Parameters are the {@code {{1}}} or {@code
 * {{name}}} placeholders, in order of first appearance.
 *
 * @param metaTemplateId Meta's id
 * @param status Meta's status (APPROVED, PENDING, PAUSED...)
 * @param named true for named parameters, false for positional ones
 */
public record MessageTemplate(
    String metaTemplateId,
    String name,
    String language,
    String category,
    String status,
    boolean supported,
    boolean named,
    String headerText,
    String bodyText,
    String footerText,
    List<String> headerParams,
    List<String> bodyParams) {
  /** Meta's limit for a body parameter. */
  public static final int VALUE_MAX = 1024;

  /** Meta's limit for the body, with its parameters filled. */
  public static final int BODY_MAX = 1024;

  /** Meta's limit for the header (with its parameter filled). */
  public static final int HEADER_MAX = 60;

  private static final Pattern PLACEHOLDER =
      Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]{1,64})\\s*\\}\\}");
  private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,512}");
  private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z]{2,3}(_[A-Za-z0-9]{2,8})?");

  public MessageTemplate {
    headerParams = List.copyOf(headerParams);
    bodyParams = List.copyOf(bodyParams);
  }

  /** A component of the template as Meta returns it. */
  public record Component(String type, String format, String text, List<Button> buttons) {}

  /** A button: its type and, for URL buttons, the URL (which may hold a parameter). */
  public record Button(String type, String url) {}

  /**
   * The template from Meta's definition, or empty when the definition itself is unusable (missing
   * id, name or language).
   */
  public static Optional<MessageTemplate> of(
      String metaTemplateId,
      String name,
      String language,
      String category,
      String status,
      String parameterFormat,
      List<Component> components) {
    if (metaTemplateId == null || !metaTemplateId.matches("[0-9]{1,32}")) return Optional.empty();
    if (name == null || !NAME.matcher(name).matches()) return Optional.empty();
    if (language == null || !LANGUAGE.matcher(language).matches()) return Optional.empty();
    String cat = upper(category, "UNKNOWN");
    String state = upper(status, "UNKNOWN");
    // Only the categories whose rules the CRM knows (opt-out applies to MARKETING).
    boolean supported = cat.equals("UTILITY") || cat.equals("MARKETING");
    String header = null;
    String body = null;
    String footer = null;
    for (Component component : components) {
      switch (upper(component.type(), "")) {
        case "HEADER" -> {
          if (!"TEXT".equals(upper(component.format(), ""))) supported = false;
          header = component.text();
        }
        case "BODY" -> body = component.text();
        case "FOOTER" -> footer = component.text();
        case "BUTTONS" -> {
          for (Button button : component.buttons()) {
            String type = upper(button.type(), "");
            boolean plain =
                type.equals("QUICK_REPLY")
                    || type.equals("PHONE_NUMBER")
                    || (type.equals("URL")
                        && (button.url() == null || !button.url().contains("{{")));
            if (!plain) supported = false;
          }
        }
        default -> supported = false;
      }
    }
    if (body == null || body.isBlank() || body.length() > 1024) supported = false;
    if (header != null && header.length() > HEADER_MAX) supported = false;
    if (footer != null && footer.length() > HEADER_MAX) supported = false;
    List<String> headerParams = params(header);
    List<String> bodyParams = params(body);
    if (headerParams.size() > 1) supported = false;
    boolean named = "NAMED".equals(upper(parameterFormat, "POSITIONAL"));
    if (!named && !(sequential(headerParams) && sequential(bodyParams))) supported = false;
    return Optional.of(
        new MessageTemplate(
            metaTemplateId,
            name,
            language,
            limited(cat, 16),
            limited(state, 20),
            supported,
            named,
            fits(header, HEADER_MAX),
            fits(body, 1024),
            fits(footer, HEADER_MAX),
            headerParams,
            bodyParams));
  }

  public boolean approved() {
    return status.equals("APPROVED");
  }

  /**
   * The text the patient sees, with the values in place.
   *
   * @throws IllegalArgumentException naming the first missing or invalid value ({@code header.KEY}
   *     or {@code body.KEY})
   */
  public String render(Map<String, String> header, Map<String, String> body) {
    check("header", headerParams, header, HEADER_MAX);
    check("body", bodyParams, body, VALUE_MAX);
    String filledHeader = fill(headerText, header);
    if (filledHeader != null && filledHeader.length() > HEADER_MAX) {
      throw new IllegalArgumentException("header." + headerParams.getFirst());
    }
    String filledBody = fill(bodyText, body);
    // Meta refuses a filled body over its own limit (error 132005).
    if (filledBody.length() > BODY_MAX) throw new IllegalArgumentException("body");
    StringBuilder text = new StringBuilder();
    if (filledHeader != null && !filledHeader.isBlank()) text.append(filledHeader).append("\n\n");
    text.append(filledBody);
    if (footerText != null && !footerText.isBlank()) text.append("\n\n").append(footerText);
    return text.toString();
  }

  private static void check(
      String section, List<String> keys, Map<String, String> values, int max) {
    Map<String, String> given = values == null ? Map.of() : values;
    for (String key : given.keySet()) {
      // An unknown key is not echoed back: only the section is named.
      if (!keys.contains(key)) throw new IllegalArgumentException(section);
    }
    for (String key : keys) {
      String value = given.get(key);
      if (!validValue(value, max)) throw new IllegalArgumentException(section + "." + key);
    }
  }

  /**
   * Meta's rules for a parameter: not blank, no line breaks, tabs or other control characters, and
   * no more than four spaces in a row.
   */
  static boolean validValue(String value, int max) {
    if (value == null || value.isBlank() || value.length() > max) return false;
    if (value.contains("     ")) return false;
    return value.chars().noneMatch(Character::isISOControl);
  }

  private static String fill(String text, Map<String, String> values) {
    if (text == null) return null;
    Map<String, String> given = values == null ? Map.of() : values;
    Matcher matcher = PLACEHOLDER.matcher(text);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String value = given.getOrDefault(matcher.group(1), matcher.group());
      matcher.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static List<String> params(String text) {
    if (text == null) return List.of();
    LinkedHashSet<String> keys = new LinkedHashSet<>();
    Matcher matcher = PLACEHOLDER.matcher(text);
    while (matcher.find()) keys.add(matcher.group(1));
    return new ArrayList<>(keys);
  }

  /** Positional parameters must be 1, 2, 3... in order of appearance. */
  private static boolean sequential(List<String> keys) {
    for (int i = 0; i < keys.size(); i++) {
      if (!keys.get(i).equals(Integer.toString(i + 1))) return false;
    }
    return true;
  }

  private static String upper(String value, String fallback) {
    return value == null || value.isBlank()
        ? fallback
        : value.strip().toUpperCase(java.util.Locale.ROOT);
  }

  private static String limited(String value, int max) {
    String cleaned = value.replaceAll("[^A-Z_]", "_");
    return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
  }

  private static String fits(String text, int max) {
    return text == null || text.length() > max ? null : text;
  }

  @Override
  public String toString() {
    return "MessageTemplate[" + name + "/" + language + ", " + status + "]";
  }
}
