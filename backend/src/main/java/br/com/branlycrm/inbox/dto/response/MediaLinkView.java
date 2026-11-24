package br.com.branlycrm.inbox.dto.response;

import java.time.Instant;

/**
 * A temporary link to a received file (CRM-084b1). It downloads as an attachment, with the type
 * detected from the file; ask for a new one after {@code expiresAt}. Never cache or share it.
 */
public record MediaLinkView(String url, String contentType, Instant expiresAt) {
  @Override
  public String toString() {
    return "MediaLinkView[redacted]";
  }
}
