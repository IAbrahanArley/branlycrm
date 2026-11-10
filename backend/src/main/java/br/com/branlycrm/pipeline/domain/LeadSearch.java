package br.com.branlycrm.pipeline.domain;

import java.util.UUID;

/** Lead list filters; any of them may be null. Without a status, only open leads are listed. */
public record LeadSearch(
    LeadStatus status, UUID stageId, UUID responsibleId, Temperature temperature, UUID contactId) {
  public LeadStatus effectiveStatus() {
    return status == null ? LeadStatus.OPEN : status;
  }
}
