package br.com.branlycrm.identity.mfa.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;

/** Six digits typed from the authenticator app; spaces are tolerated. Never logged. */
public record TotpCode(String value) {
  public TotpCode {
    if (value == null || !value.matches("[0-9]{6}")) throw new InvalidInputException("code");
  }

  public static TotpCode of(String raw) {
    if (raw == null) throw new InvalidInputException("code");
    return new TotpCode(raw.replace(" ", ""));
  }

  @Override
  public String toString() {
    return "TotpCode[redacted]";
  }
}
