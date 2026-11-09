package br.com.branlycrm.identity.mfa.dto.response;

import java.util.List;

/** Ten single-use recovery codes, shown once; only their HMACs are stored. */
public record RecoveryCodesView(List<String> recoveryCodes) {
  public RecoveryCodesView {
    recoveryCodes = List.copyOf(recoveryCodes);
  }

  @Override
  public String toString() {
    return "RecoveryCodesView[redacted]";
  }
}
