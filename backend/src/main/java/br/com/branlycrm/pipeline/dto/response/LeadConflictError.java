package br.com.branlycrm.pipeline.dto.response;

import java.util.List;
import java.util.UUID;

/** The standard error body plus the contact's open lead, only for 409 LEAD_ALREADY_OPEN. */
public record LeadConflictError(String code, String message, List<String> fields, UUID leadId) {
  public LeadConflictError {
    fields = fields == null ? List.of() : List.copyOf(fields);
  }
}
