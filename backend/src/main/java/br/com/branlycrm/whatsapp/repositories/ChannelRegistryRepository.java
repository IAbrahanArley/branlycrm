package br.com.branlycrm.whatsapp.repositories;

import br.com.branlycrm.whatsapp.domain.LiveChannel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

/**
 * Central channel registry (control database, ADR-036): routing columns only, never the sealed
 * token or PIN, which the API role cannot read.
 */
@Repository
public class ChannelRegistryRepository {
  /** At most this many channels per sweep, so a backlog never holds the worker in one pass. */
  static final int SWEEP_LIMIT = 200;

  private final JdbcTemplate jdbc;

  public ChannelRegistryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The live channel of a phone number id, whatever its status; empty for unknown numbers. */
  public Optional<UUID> findLiveChannel(String phoneNumberId) {
    if (phoneNumberId == null || !phoneNumberId.matches("[0-9]{1,32}")) return Optional.empty();
    return jdbc
        .queryForList(
            "SELECT id FROM whatsapp_channels WHERE phone_number_id = ? AND status <> 'DISCONNECTED'",
            UUID.class,
            phoneNumberId)
        .stream()
        .findFirst();
  }

  /** Routing view of the live channel of a number, for the webhook. */
  public Optional<LiveChannel> findLiveByNumber(String phoneNumberId) {
    if (phoneNumberId == null || !phoneNumberId.matches("[0-9]{1,32}")) return Optional.empty();
    return jdbc
        .query(
            "SELECT id, waba_id, display_phone_number, status FROM whatsapp_channels"
                + " WHERE phone_number_id = ? AND status <> 'DISCONNECTED'",
            (row, index) ->
                new LiveChannel(
                    row.getObject("id", UUID.class),
                    row.getString("waba_id"),
                    row.getString("display_phone_number"),
                    row.getString("status")),
            phoneNumberId)
        .stream()
        .findFirst();
  }

  /** Live channels of a WhatsApp Business Account, for account events. */
  public List<LiveChannel> findLiveByAccount(String wabaId) {
    if (wabaId == null || !wabaId.matches("[0-9]{1,32}")) return List.of();
    return jdbc.query(
        "SELECT id, waba_id, display_phone_number, status FROM whatsapp_channels"
            + " WHERE waba_id = ? AND status <> 'DISCONNECTED' ORDER BY id",
        (row, index) ->
            new LiveChannel(
                row.getObject("id", UUID.class),
                row.getString("waba_id"),
                row.getString("display_phone_number"),
                row.getString("status")),
        wabaId);
  }

  /**
   * After the clinic commit: raises the durable signal the sweep reads, then the {@code NOTIFY}
   * hint, which carries only the channel id (no personal data).
   */
  public void signalPending(UUID channelId) {
    jdbc.update(
        "UPDATE whatsapp_channels SET inbound_pending_since = now()"
            + " WHERE id = ? AND inbound_pending_since IS NULL",
        channelId);
    jdbc.query(
        "SELECT pg_notify('whatsapp_inbound', ?)",
        (ResultSetExtractor<Void>) result -> null,
        channelId.toString());
  }

  /**
   * The worker drained the clinic: clears the durable signal. The caller checks the clinic again
   * afterwards and raises it back if an event arrived in between (the webhook only raises an empty
   * signal).
   */
  public void clearPending(UUID channelId) {
    jdbc.update(
        "UPDATE whatsapp_channels SET inbound_pending_since = NULL WHERE id = ?", channelId);
  }

  /** Raises the durable signal again without a notification (events left to retry later). */
  public void keepPending(UUID channelId) {
    jdbc.update(
        "UPDATE whatsapp_channels SET inbound_pending_since = now()"
            + " WHERE id = ? AND inbound_pending_since IS NULL",
        channelId);
  }

  /** Every active channel, for daily maintenance such as retention. */
  public List<UUID> activeChannels() {
    return jdbc.queryForList(
        "SELECT id FROM whatsapp_channels WHERE status = 'ACTIVE' ORDER BY id", UUID.class);
  }

  /** Active channels whose webhook left events waiting, oldest first. */
  public List<UUID> channelsWithPendingEvents() {
    return jdbc.queryForList(
        """
        SELECT c.id FROM whatsapp_channels c JOIN tenants t ON t.tenant_id = c.tenant_id
        WHERE c.status = 'ACTIVE' AND t.status = 'ACTIVE' AND c.inbound_pending_since IS NOT NULL
        ORDER BY c.inbound_pending_since, c.id
        LIMIT ?
        """,
        UUID.class,
        SWEEP_LIMIT);
  }
}
