package br.com.branlycrm.whatsapp.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Routing view of a clinic's live channel for the people who manage it (CRM-081b): never the sealed
 * token or PIN, nor the account and number ids.
 *
 * @param status PENDING, ACTIVE or FAILED
 * @param openStep the step of a connection or disconnection still running, or null
 */
public record ChannelState(
    UUID channelId,
    String status,
    String displayPhoneNumber,
    String failureCode,
    String openStep,
    Instant connectedAt,
    Instant updatedAt) {
  public ChannelState {
    Objects.requireNonNull(channelId, "channelId");
    Objects.requireNonNull(status, "status");
  }
}
