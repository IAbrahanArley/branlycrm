package br.com.branlycrm.pipeline.domain;

import java.util.UUID;

/** An archived open stage of the funnel: shown only to unarchive it (CRM-036). */
public record ArchivedStage(UUID id, String name) {
  public ArchivedStage {
    if (id == null || name == null || name.isBlank()) {
      throw new IllegalArgumentException("Incomplete archived stage");
    }
  }
}
