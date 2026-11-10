package br.com.branlycrm.pipeline.domain;

import java.util.UUID;

/** A loss reason of the clinic (CRM-06). Archived ones stay on old leads and reports. */
public record LossReason(UUID id, String name, boolean archived) {
  public LossReason {
    if (id == null || name == null || name.isBlank()) {
      throw new IllegalArgumentException("Incomplete loss reason");
    }
  }
}
