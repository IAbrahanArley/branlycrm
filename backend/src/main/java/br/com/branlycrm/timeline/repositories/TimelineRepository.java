package br.com.branlycrm.timeline.repositories;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import br.com.branlycrm.timeline.domain.TimelineEntry;
import br.com.branlycrm.timeline.exceptions.TimelineException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

/**
 * A contact's timeline (CRM-023): events and notes of every lead of the contact, sale events and
 * task creation and completion, newest first. Read only, in one transaction of the authorized
 * clinic; the page and its total come from one statement.
 *
 * <p>Order is stable: instant, then the source ({@code rank}), then the write order ({@code seq})
 * and the id. Within one instant a sale event comes above the lead event of the same write, and a
 * task completion above its creation. A reopened task loses its completion entry, because the task
 * keeps only its current completion. {@code kind} plus {@code id} identify an entry: both task
 * entries carry the task id.
 */
@Repository
public class TimelineRepository {
  private static final String ENTRIES =
      """
      SELECT 'LEAD_EVENT' AS kind, e.id, e.occurred_at, e.actor_id, e.lead_id,
             CAST(NULL AS UUID) AS sale_id, CAST(NULL AS UUID) AS task_id, e.type,
             e.from_value, e.to_value, e.loss_reason_id, CAST(NULL AS BIGINT) AS amount_cents,
             CAST(NULL AS VARCHAR) AS text, 1 AS rank, e.seq
      FROM lead_events e JOIN leads l ON l.id = e.lead_id
      WHERE l.contact_id = :contact
      UNION ALL
      SELECT 'NOTE', n.id, n.created_at, n.author_id, n.lead_id, NULL, NULL, NULL, NULL, NULL,
             NULL, NULL, n.body, 2, n.seq
      FROM lead_notes n JOIN leads l ON l.id = n.lead_id
      WHERE l.contact_id = :contact
      UNION ALL
      SELECT 'SALE_EVENT', se.id, se.occurred_at, se.actor_id, s.lead_id, s.id, NULL, se.type,
             NULL, NULL, NULL, se.amount_cents, se.reason, 3, se.seq
      FROM sale_events se JOIN sales s ON s.id = se.sale_id
      WHERE s.contact_id = :contact
      UNION ALL
      SELECT 'TASK_CREATED', t.id, t.created_at, t.created_by, t.lead_id, NULL, t.id, NULL,
             NULL, NULL, NULL, NULL, t.title, 4, 0
      FROM tasks t
      WHERE t.contact_id = :contact
      UNION ALL
      SELECT 'TASK_COMPLETED', t.id, t.completed_at, t.completed_by, t.lead_id, NULL, t.id, NULL,
             NULL, NULL, NULL, NULL, t.title, 5, 0
      FROM tasks t
      WHERE t.contact_id = :contact AND t.completed_at IS NOT NULL
      """;

  private final ClinicPersistence persistence;

  public TimelineRepository(ClinicPersistence persistence) {
    this.persistence = persistence;
  }

  public record Page(List<TimelineEntry> entries, long total) {}

  /**
   * @throws TimelineException when the contact is missing in this clinic (archived ones included)
   */
  public Page page(ClinicAccess access, UUID contactId, PageQuery query) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          boolean exists =
              !session
                  .createNativeQuery("SELECT 1 FROM contacts WHERE id = :contact", Integer.class)
                  .setParameter("contact", contactId)
                  .getResultList()
                  .isEmpty();
          if (!exists) throw new TimelineException();
          // The total comes from the same statement, so it shares the page's snapshot.
          List<Object[]> rows =
              session
                  .createNativeQuery(
                      "SELECT kind, id, occurred_at, actor_id, lead_id, sale_id, task_id, type,"
                          + " from_value, to_value, loss_reason_id, amount_cents, text,"
                          + " count(*) OVER () FROM ("
                          + ENTRIES
                          + ") entries ORDER BY occurred_at DESC, rank DESC, seq DESC, id DESC"
                          + " LIMIT :limit OFFSET :offset",
                      Object[].class)
                  .setParameter("contact", contactId)
                  .setParameter("limit", query.pageSize())
                  .setParameter("offset", query.offset())
                  .getResultList();
          long total =
              rows.isEmpty()
                  ? (query.offset() == 0 ? 0 : count(session, contactId))
                  : ((Number) rows.getFirst()[13]).longValue();
          return new Page(rows.stream().map(TimelineRepository::entry).toList(), total);
        });
  }

  /** Total for a page past the end, where the windowed count has no row to ride on. */
  private static long count(Session session, UUID contactId) {
    return session
        .createNativeQuery("SELECT count(*) FROM (" + ENTRIES + ") entries", Long.class)
        .setParameter("contact", contactId)
        .getSingleResult();
  }

  private static TimelineEntry entry(Object[] row) {
    return new TimelineEntry(
        (String) row[0],
        (UUID) row[1],
        instant(row[2]),
        (UUID) row[3],
        (UUID) row[4],
        (UUID) row[5],
        (UUID) row[6],
        (String) row[7],
        (String) row[8],
        (String) row[9],
        (UUID) row[10],
        row[11] == null ? null : ((Number) row[11]).longValue(),
        (String) row[12]);
  }

  private static Instant instant(Object value) {
    if (value instanceof Instant instant) return instant;
    if (value instanceof OffsetDateTime offset) return offset.toInstant();
    return ((java.sql.Timestamp) value).toInstant();
  }
}
