package br.com.branlycrm.whatsapp.domain;

import java.util.Objects;
import java.util.UUID;

/** Routing view of a central channel that is not disconnected (no secrets). */
public record LiveChannel(UUID id, String wabaId, String displayPhone, String status) {
  public LiveChannel {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
  }

  /** Same number, comparing digits only ("+55 11 ..." and "5511..." match). */
  public boolean displays(String phone) {
    if (phone == null || displayPhone == null) return false;
    String digits = phone.replaceAll("[^0-9]", "");
    return !digits.isEmpty() && digits.equals(displayPhone.replaceAll("[^0-9]", ""));
  }

  public boolean active() {
    return "ACTIVE".equals(status);
  }
}
