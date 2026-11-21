package br.com.branlycrm.whatsapp.domain;

/**
 * The {@code referral} block of a message that came from a Meta ad or boosted post ("Click to
 * WhatsApp"). Raw values from Meta, validated by {@link OriginDetector}.
 *
 * @param sourceType {@code ad} or {@code post}
 * @param sourceId the ad or post id
 * @param headline the ad title
 * @param ctwaClid Meta's click id, stored only for attribution and never returned by the API
 */
public record Referral(String sourceType, String sourceId, String headline, String ctwaClid) {
  @Override
  public String toString() {
    return "Referral[sourceType=" + sourceType + "]";
  }
}
