package br.com.branlycrm.contact.domain;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Consent rules (PAC-08, RN-05, ADR-032).
 *
 * <ul>
 *   <li>On each channel the most recent record prevails; at the same instant opt-out wins.
 *   <li>A channel without records is NONE.
 *   <li>An import never turns an opt-out back into an opt-in: only the person, recorded by the team
 *       or asked in a conversation, can do that.
 * </ul>
 */
public final class ConsentPolicy {
  /** Most recent first; at the same instant, opt-out first. */
  public static final Comparator<ConsentRecord> PRECEDENCE =
      Comparator.comparing(ConsentRecord::recordedAt)
          .reversed()
          .thenComparing(record -> record.status() == ConsentStatus.OPT_OUT ? 0 : 1);

  private ConsentPolicy() {}

  public static Map<ConsentChannel, ConsentState> current(List<ConsentRecord> history) {
    Map<ConsentChannel, ConsentState> state = new EnumMap<>(ConsentChannel.class);
    for (ConsentChannel channel : ConsentChannel.values()) {
      state.put(
          channel,
          history.stream()
              .filter(record -> record.channel() == channel)
              .min(PRECEDENCE)
              .map(record -> stateOf(record.status()))
              .orElse(ConsentState.NONE));
    }
    return state;
  }

  /** Whether a new record may be added on top of the channel's current state. */
  public static boolean accepts(ConsentStatus status, ConsentSource source, ConsentState current) {
    return !(status == ConsentStatus.OPT_IN
        && source == ConsentSource.IMPORT
        && current == ConsentState.OPT_OUT);
  }

  private static ConsentState stateOf(ConsentStatus status) {
    return status == ConsentStatus.OPT_IN ? ConsentState.OPT_IN : ConsentState.OPT_OUT;
  }
}
