package br.com.branlycrm.whatsapp.repositories;

import br.com.branlycrm.whatsapp.secrets.SealedSecret;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The worker side of the channel connection (CRM-081b, control database, role {@code
 * crm_whatsapp_worker}): leases a task, reads the sealed secrets and moves channel and task through
 * the steps. It never changes a channel's clinic, id or author.
 *
 * <p>Every write that ends or moves a task holds the lease: it applies only while the task is
 * RUNNING with the same {@code lease_until} this worker set, so a slow or resumed worker never
 * overwrites the work of another. Resuming a task whose lease expired (the worker died) spends an
 * attempt, so a task that kills the process cannot loop forever.
 */
@Repository
@ConditionalOnProperty(name = "app.whatsapp.worker.enabled", havingValue = "true")
public class ChannelOnboardingRepository {
  private final JdbcTemplate jdbc;

  public ChannelOnboardingRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A task leased by this worker, with what its step needs. Secrets stay sealed here and never
   * appear in {@link #toString()}.
   *
   * @param attempts already spent, including a resumed lease
   * @param leaseUntil the lease this worker holds; every write checks it
   */
  public record LeasedTask(
      UUID taskId,
      UUID channelId,
      String step,
      int attempts,
      Instant leaseUntil,
      String channelStatus,
      String wabaId,
      String phoneNumberId,
      String requestedPhoneNumberId,
      boolean coexistence,
      SealedSecret token,
      SealedSecret pin) {
    @Override
    public String toString() {
      return "LeasedTask[task=" + taskId + ", channel=" + channelId + ", step=" + step + "]";
    }
  }

  /** Leases the next due task (PENDING, or RUNNING with an expired lease). */
  @Transactional
  public Optional<LeasedTask> lease(Instant now, Duration lease) {
    Instant until = now.plus(lease).truncatedTo(ChronoUnit.MICROS);
    List<Object[]> rows =
        jdbc.query(
            """
            SELECT t.id, t.channel_id, t.step, t.attempts, t.status,
                   t.requested_phone_number_id, t.coexistence, c.status AS channel_status,
                   c.waba_id, c.phone_number_id, c.sealed_token, c.sealed_pin, c.key_version
            FROM whatsapp_onboarding_tasks t JOIN whatsapp_channels c ON c.id = t.channel_id
            WHERE t.next_attempt_at <= ?
              AND (t.status = 'PENDING' OR (t.status = 'RUNNING' AND t.lease_until < ?))
            ORDER BY t.next_attempt_at, t.id
            LIMIT 1
            FOR UPDATE OF t SKIP LOCKED
            """,
            (row, index) ->
                new Object[] {
                  row.getObject("id", UUID.class),
                  row.getObject("channel_id", UUID.class),
                  row.getString("step"),
                  row.getInt("attempts"),
                  row.getString("status"),
                  row.getString("requested_phone_number_id"),
                  row.getBoolean("coexistence"),
                  row.getString("channel_status"),
                  row.getString("waba_id"),
                  row.getString("phone_number_id"),
                  row.getBytes("sealed_token"),
                  row.getBytes("sealed_pin"),
                  row.getObject("key_version", Integer.class)
                },
            Timestamp.from(now),
            Timestamp.from(now));
    if (rows.isEmpty()) return Optional.empty();
    Object[] row = rows.getFirst();
    // A RUNNING task with an expired lease: the worker that held it stopped mid-step.
    int attempts = (int) row[3] + ("RUNNING".equals(row[4]) ? 1 : 0);
    jdbc.update(
        "UPDATE whatsapp_onboarding_tasks SET status = 'RUNNING', attempts = ?, lease_until = ?,"
            + " updated_at = ? WHERE id = ?",
        attempts,
        Timestamp.from(until),
        Timestamp.from(now),
        row[0]);
    Integer version = (Integer) row[12];
    byte[] token = (byte[]) row[10];
    byte[] pin = (byte[]) row[11];
    return Optional.of(
        new LeasedTask(
            (UUID) row[0],
            (UUID) row[1],
            (String) row[2],
            attempts,
            until,
            (String) row[7],
            (String) row[8],
            (String) row[9],
            (String) row[5],
            (Boolean) row[6],
            token == null || version == null ? null : new SealedSecret(token, version),
            pin == null || version == null ? null : new SealedSecret(pin, version)));
  }

  /**
   * Records the account and number confirmed from the token.
   *
   * @return false when the number already belongs to another live channel
   */
  public boolean confirmAssets(
      UUID channelId, String wabaId, String phoneNumberId, String display, Instant at) {
    try {
      jdbc.update(
          "UPDATE whatsapp_channels SET waba_id = ?, phone_number_id = ?,"
              + " display_phone_number = ?, updated_at = ?, version = version + 1"
              + " WHERE id = ? AND status = 'PENDING'",
          wabaId,
          phoneNumberId,
          display == null || display.isBlank() ? null : display,
          Timestamp.from(at),
          channelId);
      return true;
    } catch (DuplicateKeyException claimed) {
      return false;
    }
  }

  /** Token and PIN sealed again together, with the newest key (one key version per channel). */
  public void storeSecrets(UUID channelId, SealedSecret token, SealedSecret pin, Instant at) {
    if (token.keyVersion() != pin.keyVersion()) throw new IllegalArgumentException("key version");
    jdbc.update(
        "UPDATE whatsapp_channels SET sealed_token = ?, sealed_pin = ?, key_version = ?,"
            + " updated_at = ?, version = version + 1 WHERE id = ? AND status = 'PENDING'",
        token.value(),
        pin.value(),
        token.keyVersion(),
        Timestamp.from(at),
        channelId);
  }

  /**
   * Another live channel shares the account: the account's webhook subscription serves it too and
   * must stay (an agency may keep several clinics' numbers in one account).
   */
  public boolean otherLiveOnAccount(UUID channelId, String wabaId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM whatsapp_channels WHERE waba_id = ? AND id <> ?"
                + " AND status <> 'DISCONNECTED')",
            Boolean.class,
            wabaId,
            channelId));
  }

  /** The task moves to its next step, due now, with fresh attempts. */
  public boolean advance(LeasedTask task, String step, Instant at) {
    return jdbc.update(
            "UPDATE whatsapp_onboarding_tasks SET step = ?, status = 'PENDING', attempts = 0,"
                + " next_attempt_at = ?, lease_until = NULL, last_error = NULL, updated_at = ?"
                + " WHERE id = ? AND status = 'RUNNING' AND lease_until = ?",
            step,
            Timestamp.from(at),
            Timestamp.from(at),
            task.taskId(),
            Timestamp.from(task.leaseUntil()))
        == 1;
  }

  /** The channel goes live and the task ends. */
  @Transactional
  public void activate(LeasedTask task, Instant at) {
    if (!finish(task, "DONE", null, at)) return;
    jdbc.update(
        "UPDATE whatsapp_channels SET status = 'ACTIVE', failure_code = NULL, updated_at = ?,"
            + " version = version + 1 WHERE id = ? AND status = 'PENDING'",
        Timestamp.from(at),
        task.channelId());
  }

  /** The connection failed for good: the channel shows the code and the task ends. */
  @Transactional
  public void fail(LeasedTask task, String code, Instant at) {
    if (!finish(task, "FAILED", code, at)) return;
    jdbc.update(
        "UPDATE whatsapp_channels SET status = 'FAILED', failure_code = ?, updated_at = ?,"
            + " version = version + 1 WHERE id = ? AND status IN ('PENDING', 'ACTIVE')",
        code,
        Timestamp.from(at),
        task.channelId());
  }

  /** Disconnected: secrets wiped, events arriving later are discarded (ADR-036). */
  @Transactional
  public void disconnect(LeasedTask task, Instant at) {
    if (!finish(task, "DONE", null, at)) return;
    jdbc.update(
        "UPDATE whatsapp_channels SET status = 'DISCONNECTED', sealed_token = NULL,"
            + " sealed_pin = NULL, failure_code = NULL, inbound_pending_since = NULL,"
            + " disconnected_at = ?, updated_at = ?, version = version + 1"
            + " WHERE id = ? AND status <> 'DISCONNECTED'",
        Timestamp.from(at),
        Timestamp.from(at),
        task.channelId());
  }

  /** Ends a task whose channel no longer needs it (already disconnected, for example). */
  public void drop(LeasedTask task, String code, Instant at) {
    finish(task, "DONE", code, at);
  }

  /** Another try later; the code is stable and safe to store. */
  public void retry(LeasedTask task, int attempts, Instant next, String code, Instant at) {
    jdbc.update(
        "UPDATE whatsapp_onboarding_tasks SET status = 'PENDING', attempts = ?,"
            + " next_attempt_at = ?, lease_until = NULL, last_error = ?, updated_at = ?"
            + " WHERE id = ? AND status = 'RUNNING' AND lease_until = ?",
        attempts,
        Timestamp.from(next),
        code,
        Timestamp.from(at),
        task.taskId(),
        Timestamp.from(task.leaseUntil()));
  }

  /** False when this worker lost the lease meanwhile: the other holder decides. */
  private boolean finish(LeasedTask task, String status, String code, Instant at) {
    return jdbc.update(
            "UPDATE whatsapp_onboarding_tasks SET status = ?, lease_until = NULL, last_error = ?,"
                + " updated_at = ? WHERE id = ? AND status = 'RUNNING' AND lease_until = ?",
            status,
            code,
            Timestamp.from(at),
            task.taskId(),
            Timestamp.from(task.leaseUntil()))
        == 1;
  }
}
