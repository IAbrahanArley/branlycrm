package br.com.branlycrm.whatsapp.repositories;

import br.com.branlycrm.whatsapp.domain.ChannelState;
import br.com.branlycrm.whatsapp.secrets.SealedSecret;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The API side of the channel connection (CRM-081b, control database): it reads routing columns,
 * creates a PENDING channel with the token already sealed and creates tasks, always PENDING. Only
 * the worker changes a channel's status (ADR-036).
 */
@Repository
public class ChannelConnectionRepository {
  private final JdbcTemplate jdbc;

  public ChannelConnectionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The clinic's live channel (not disconnected), with the step of a task still open. */
  public Optional<ChannelState> liveChannel(UUID tenantId) {
    return jdbc
        .query(
            """
            SELECT c.id, c.status, c.display_phone_number, c.failure_code, c.created_at,
                   c.updated_at,
                   (SELECT t.step FROM whatsapp_onboarding_tasks t
                    WHERE t.channel_id = c.id AND t.status IN ('PENDING', 'RUNNING')
                    ORDER BY t.created_at DESC LIMIT 1) AS open_step
            FROM whatsapp_channels c
            WHERE c.tenant_id = ? AND c.status <> 'DISCONNECTED'
            """,
            ChannelConnectionRepository::state,
            tenantId)
        .stream()
        .findFirst();
  }

  /**
   * Creates the PENDING channel and its connection task in one transaction.
   *
   * @return false when the clinic already has a live channel (unique index, also under races)
   */
  @Transactional
  public boolean createPending(
      UUID channelId,
      UUID tenantId,
      SealedSecret token,
      UUID actor,
      String requestedPhoneNumberId,
      boolean coexistence,
      Instant at) {
    Timestamp now = Timestamp.from(at);
    // ON CONFLICT: a unique violation would abort the transaction; this answers "already live"
    // without an error, also under concurrent requests.
    int inserted =
        jdbc.update(
            "INSERT INTO whatsapp_channels (id, tenant_id, status, sealed_token, key_version,"
                + " connected_by, created_at, updated_at)"
                + " VALUES (?, ?, 'PENDING', ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            channelId,
            tenantId,
            token.value(),
            token.keyVersion(),
            actor,
            now,
            now);
    if (inserted == 0) return false;
    insertTask(channelId, "VERIFY_ASSETS", actor, requestedPhoneNumberId, coexistence, now);
    return true;
  }

  /**
   * Asks the worker to disconnect the channel.
   *
   * @return false when a connection or disconnection of this channel is still running
   */
  public boolean requestDisconnect(UUID channelId, UUID actor, Instant at) {
    return insertTask(channelId, "DISCONNECT", actor, null, false, Timestamp.from(at));
  }

  /** The clinic belongs to the agency's portfolio and is active. */
  public boolean inPortfolio(UUID agencyId, UUID tenantId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM tenants WHERE tenant_id = ? AND agency_id = ?"
                + " AND status = 'ACTIVE')",
            Boolean.class,
            tenantId,
            agencyId));
  }

  /** False when the channel already has an open task (unique index). */
  private boolean insertTask(
      UUID channelId,
      String step,
      UUID actor,
      String requestedPhoneNumberId,
      boolean coexistence,
      Timestamp now) {
    return jdbc.update(
            "INSERT INTO whatsapp_onboarding_tasks (id, channel_id, idempotency_key, step,"
                + " next_attempt_at, requested_by, requested_phone_number_id, coexistence,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT DO NOTHING",
            UUID.randomUUID(),
            channelId,
            UUID.randomUUID(),
            step,
            now,
            actor,
            requestedPhoneNumberId,
            coexistence,
            now,
            now)
        == 1;
  }

  private static ChannelState state(ResultSet row, int index) throws SQLException {
    return new ChannelState(
        row.getObject("id", UUID.class),
        row.getString("status"),
        row.getString("display_phone_number"),
        row.getString("failure_code"),
        row.getString("open_step"),
        row.getTimestamp("created_at").toInstant(),
        row.getTimestamp("updated_at").toInstant());
  }
}
