package br.com.branlycrm.whatsapp.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
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

  /** Active channels whose webhook left events waiting, oldest first. */
  public List<UUID> channelsWithPendingEvents() {
    return jdbc.queryForList(
        """
        SELECT id FROM whatsapp_channels
        WHERE status = 'ACTIVE' AND inbound_pending_since IS NOT NULL
        ORDER BY inbound_pending_since, id
        LIMIT ?
        """,
        UUID.class,
        SWEEP_LIMIT);
  }
}
