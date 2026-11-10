package br.com.branlycrm.contact.dto.response;

/**
 * Duplicate warning before creating: the normalized number and the contact that already holds it in
 * this clinic (archived ones included), or null.
 */
public record PhoneCheckView(String phone, ContactSummaryView contact) {
  @Override
  public String toString() {
    return "PhoneCheckView[***]";
  }
}
