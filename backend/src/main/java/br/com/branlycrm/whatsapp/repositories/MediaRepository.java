package br.com.branlycrm.whatsapp.repositories;

import br.com.branlycrm.tenant.domain.ChannelAccess;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * The worker side of {@code whatsapp_media} (CRM-084b1), in the channel's clinic database. A media
 * is claimed by pushing its next attempt forward (a lease), downloaded and stored outside any
 * transaction, and its result recorded afterwards.
 */
@Repository
public class MediaRepository {
  public static final int MAX_ATTEMPTS = 5;

  private final ClinicPersistence persistence;

  public MediaRepository(ClinicPersistence persistence) {
    this.persistence = persistence;
  }

  /** A media to download: its row id and Meta's id. */
  public record Pending(UUID id, String metaMediaId, int attempts) {}

  /** Claims the oldest due media: one more attempt, not due again before {@code lease}. */
  public Optional<Pending> claim(ChannelAccess access, Instant now, Duration lease) {
    return persistence.inTransaction(
        ClinicTenant.ofChannel(access),
        session -> {
          // A media that kept failing (or crashing the step) stops here, as a fact.
          session
              .createNativeMutationQuery(
                  "UPDATE whatsapp_media SET status = 'FAILED', error_code = 'MEDIA_EXHAUSTED',"
                      + " updated_at = :now WHERE status = 'PENDING' AND attempts >= :max")
              .setParameter("now", OutboxRepository.utc(now))
              .setParameter("max", MAX_ATTEMPTS)
              .executeUpdate();
          List<Object[]> rows =
              session
                  .createNativeQuery(
                      """
                      SELECT id, meta_media_id, attempts FROM whatsapp_media
                      WHERE status = 'PENDING' AND next_attempt_at <= :now
                      ORDER BY next_attempt_at, id
                      LIMIT 1
                      FOR UPDATE SKIP LOCKED
                      """,
                      Object[].class)
                  .setParameter("now", OutboxRepository.utc(now))
                  .getResultList();
          if (rows.isEmpty()) return Optional.empty();
          Object[] row = rows.getFirst();
          int attempts = ((Number) row[2]).intValue() + 1;
          session
              .createNativeMutationQuery(
                  "UPDATE whatsapp_media SET attempts = :attempts, next_attempt_at = :until,"
                      + " updated_at = :now WHERE id = :id")
              .setParameter("attempts", attempts)
              .setParameter("until", OutboxRepository.utc(now.plus(lease)))
              .setParameter("now", OutboxRepository.utc(now))
              .setParameter("id", row[0])
              .executeUpdate();
          return Optional.of(new Pending((UUID) row[0], (String) row[1], attempts));
        });
  }

  public void stored(
      ChannelAccess access,
      UUID id,
      String key,
      String contentType,
      long size,
      String sha256,
      Instant at) {
    update(
        access,
        id,
        "status = 'STORED', storage_key = :key, content_type = :type, size_bytes = :size,"
            + " sha256 = :sha, error_code = NULL",
        new Object[] {"key", key, "type", contentType, "size", size, "sha", sha256},
        at);
  }

  /** Not accepted (type or size): kept as a fact, never downloaded again. */
  public void reject(ChannelAccess access, UUID id, String code, Instant at) {
    update(
        access,
        id,
        "status = 'REJECTED', error_code = :code",
        new Object[] {"code", safe(code)},
        at);
  }

  public void fail(ChannelAccess access, UUID id, String code, Instant at) {
    update(
        access, id, "status = 'FAILED', error_code = :code", new Object[] {"code", safe(code)}, at);
  }

  /** A code the column accepts ({@code [A-Z0-9_]}, at most 40), whatever produced it. */
  static String safe(String code) {
    String clean =
        code == null ? "" : code.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    if (clean.isEmpty()) clean = "MEDIA_ERROR";
    return clean.length() > 40 ? clean.substring(0, 40) : clean;
  }

  public void retry(ChannelAccess access, UUID id, Instant next, Instant at) {
    update(
        access,
        id,
        "next_attempt_at = :next",
        new Object[] {"next", OutboxRepository.utc(next)},
        at);
  }

  private void update(ChannelAccess access, UUID id, String set, Object[] parameters, Instant at) {
    persistence.inTransaction(
        ClinicTenant.ofChannel(access),
        session -> {
          var update =
              session.createNativeMutationQuery(
                  "UPDATE whatsapp_media SET "
                      + set
                      + ", updated_at = :at WHERE id = :id AND status = 'PENDING'");
          for (int i = 0; i < parameters.length; i += 2) {
            update.setParameter((String) parameters[i], parameters[i + 1]);
          }
          return update
              .setParameter("at", OutboxRepository.utc(at))
              .setParameter("id", id)
              .executeUpdate();
        });
  }
}
