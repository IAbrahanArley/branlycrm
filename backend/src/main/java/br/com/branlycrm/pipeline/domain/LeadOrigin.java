package br.com.branlycrm.pipeline.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import java.util.regex.Pattern;

/**
 * Where a lead came from (ORI-01): the type the reports group by, how it was known, and the raw ad
 * id, title or link code (campaigns are F2, ORI-06). {@code ctwaClid} is Meta's click id, kept for
 * attribution only.
 */
public record LeadOrigin(
    Type type, Source source, String adId, String code, String title, String ctwaClid) {
  public enum Type {
    PAID_TRAFFIC,
    INSTAGRAM_ORGANIC,
    REFERRAL,
    GOOGLE,
    OTHER,
    UNIDENTIFIED
  }

  public enum Source {
    /** The referral block of a Meta "Click to WhatsApp" ad (CRM-091). */
    AD,
    /** A [CODE] in the first message (CRM-091). */
    CODE,
    /** Chosen or corrected by a person. */
    MANUAL,
    /** Nothing detected. */
    NONE
  }

  public static final int AD_ID_MAX = 64;
  public static final int TITLE_MAX = 200;
  public static final int CLID_MAX = 512;
  private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{1,32}");
  private static final Pattern VISIBLE_ASCII = Pattern.compile("[\\x21-\\x7E]+");

  public LeadOrigin {
    if (type == null) throw new InvalidInputException("type");
    if (source == null) throw new IllegalArgumentException("Origin without source");
    adId = blankToNull(adId);
    if (adId != null && (adId.length() > AD_ID_MAX || !VISIBLE_ASCII.matcher(adId).matches())) {
      throw new InvalidInputException("adId");
    }
    code = blankToNull(code);
    if (code != null && !CODE.matcher(code).matches()) throw new InvalidInputException("code");
    title = blankToNull(title);
    if (title != null
        && (title.length() > TITLE_MAX
            || title.codePoints().anyMatch(c -> Character.isISOControl(c) || invisible(c)))) {
      throw new InvalidInputException("title");
    }
    ctwaClid = blankToNull(ctwaClid);
    if (ctwaClid != null
        && (ctwaClid.length() > CLID_MAX || !VISIBLE_ASCII.matcher(ctwaClid).matches())) {
      throw new InvalidInputException("ctwaClid");
    }
  }

  public static LeadOrigin unidentified() {
    return new LeadOrigin(Type.UNIDENTIFIED, Source.NONE, null, null, null, null);
  }

  /** Chosen by a person: only the type, a link code and a title. */
  public static LeadOrigin manual(Type type, String code, String title) {
    return new LeadOrigin(type, Source.MANUAL, null, code, title, null);
  }

  /**
   * A manual correction of this origin: the detected ad id and click id stay as evidence; type,
   * code and title are replaced.
   */
  public LeadOrigin correctedTo(LeadOrigin manual) {
    return new LeadOrigin(manual.type, Source.MANUAL, adId, manual.code, manual.title, ctwaClid);
  }

  private static String blankToNull(String value) {
    if (value == null) return null;
    String text = value.strip();
    return text.isEmpty() ? null : text;
  }

  private static boolean invisible(int c) {
    int kind = Character.getType(c);
    return kind == Character.FORMAT
        || kind == Character.LINE_SEPARATOR
        || kind == Character.PARAGRAPH_SEPARATOR;
  }

  /** The click id is a tracking identifier: kept out of logs. */
  @Override
  public String toString() {
    return "LeadOrigin[type=" + type + ", source=" + source + "]";
  }
}
