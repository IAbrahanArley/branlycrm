package br.com.branlycrm.whatsapp.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.pipeline.domain.LeadOrigin;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatic origin of a WhatsApp arrival (CRM-091, ADR-035):
 *
 * <ul>
 *   <li>a {@code referral} from an ad or a boosted post is paid traffic, with the ad id, title and
 *       click id;
 *   <li>otherwise a {@code [CODE]} at the start of the text is the link's code, of type Other
 *       (campaign names are F2, ORI-06: reports group by the code);
 *   <li>otherwise nothing was identified.
 * </ul>
 *
 * Invalid pieces from Meta are dropped one by one, never failing the message.
 */
public final class OriginDetector {
  private static final Pattern CODE = Pattern.compile("^\\s*\\[([A-Za-z0-9_-]{1,32})\\]");
  private static final Pattern HIDDEN = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

  private OriginDetector() {}

  public static LeadOrigin detect(Referral referral, String text) {
    if (referral != null
        && ("ad".equals(referral.sourceType()) || "post".equals(referral.sourceType()))) {
      return ad(referral);
    }
    if (text != null) {
      Matcher code = CODE.matcher(text);
      if (code.find()) {
        return new LeadOrigin(
            LeadOrigin.Type.OTHER, LeadOrigin.Source.CODE, null, code.group(1), null, null);
      }
    }
    return LeadOrigin.unidentified();
  }

  private static LeadOrigin ad(Referral referral) {
    String adId = valid(referral.sourceId(), LeadOrigin.AD_ID_MAX);
    String clid = valid(referral.ctwaClid(), LeadOrigin.CLID_MAX);
    String title = title(referral.headline());
    try {
      return new LeadOrigin(
          LeadOrigin.Type.PAID_TRAFFIC, LeadOrigin.Source.AD, adId, null, title, clid);
    } catch (InvalidInputException invalid) {
      // A value the lead refuses (for example a non-ASCII id): keep only the type.
      return new LeadOrigin(
          LeadOrigin.Type.PAID_TRAFFIC, LeadOrigin.Source.AD, null, null, null, null);
    }
  }

  private static String valid(String value, int max) {
    if (value == null) return null;
    String stripped = value.strip();
    return stripped.isEmpty() || stripped.length() > max || !stripped.matches("[\\x21-\\x7E]+")
        ? null
        : stripped;
  }

  private static String title(String headline) {
    if (headline == null) return null;
    String clean = HIDDEN.matcher(headline).replaceAll(" ").strip().replaceAll(" {2,}", " ");
    if (clean.isEmpty()) return null;
    if (clean.length() > LeadOrigin.TITLE_MAX) {
      clean = clean.substring(0, cut(clean)).strip();
    }
    return clean.isEmpty() ? null : clean;
  }

  /** Index at most TITLE_MAX that never splits a surrogate pair. */
  private static int cut(String text) {
    int end = LeadOrigin.TITLE_MAX;
    if (Character.isLowSurrogate(text.charAt(end))) end--;
    return end;
  }
}
