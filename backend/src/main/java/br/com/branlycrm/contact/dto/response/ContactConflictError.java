package br.com.branlycrm.contact.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * The standard error body plus the contact that already holds the number, only for 409
 * CONTACT_DUPLICATE_PHONE. {@code contactId} is null when a concurrent change made it unknown.
 */
public record ContactConflictError(
    String code, String message, List<String> fields, UUID contactId) {
  public ContactConflictError {
    fields = fields == null ? List.of() : List.copyOf(fields);
  }
}
