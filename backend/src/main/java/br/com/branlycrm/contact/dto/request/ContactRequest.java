package br.com.branlycrm.contact.dto.request;

/**
 * Contact data for creation and full replacement. Dates and the phone arrive as text so an invalid
 * value is reported by field name. {@code version} is required to edit and ignored on creation; an
 * absent optional field is cleared on edit.
 */
public record ContactRequest(
    String name, String phone, String email, String birthDate, String notes, Long version) {
  /** Keeps personal data (and notes, which may hold health data) out of logs. */
  @Override
  public String toString() {
    return "ContactRequest[***]";
  }
}
