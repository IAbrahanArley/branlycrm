package br.com.branlycrm.contact.dto.request;

/** The number to check before creating a contact; in the body, never in the URL or the log. */
public record PhoneCheckRequest(String phone) {
  @Override
  public String toString() {
    return "PhoneCheckRequest[***]";
  }
}
