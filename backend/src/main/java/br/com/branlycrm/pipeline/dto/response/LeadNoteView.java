package br.com.branlycrm.pipeline.dto.response;

import java.time.Instant;
import java.util.UUID;

public record LeadNoteView(UUID id, String body, UUID authorId, Instant createdAt) {
  @Override
  public String toString() {
    return "LeadNoteView[id=" + id + "]";
  }
}
