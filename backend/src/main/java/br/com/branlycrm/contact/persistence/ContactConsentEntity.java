package br.com.branlycrm.contact.persistence;

import br.com.branlycrm.contact.domain.ConsentChannel;
import br.com.branlycrm.contact.domain.ConsentRecord;
import br.com.branlycrm.contact.domain.ConsentSource;
import br.com.branlycrm.contact.domain.ConsentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One entry of contact_consents (tenant migration V4): inserted once, never updated. */
@Entity
@Table(name = "contact_consents")
@Immutable
public class ContactConsentEntity {
  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "contact_id", nullable = false)
  private UUID contactId;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel", nullable = false, length = 16)
  private ConsentChannel channel;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 8)
  private ConsentStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 16)
  private ConsentSource source;

  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt;

  @Column(name = "recorded_by")
  private UUID recordedBy;

  protected ContactConsentEntity() {}

  public ContactConsentEntity(
      UUID contactId,
      ConsentChannel channel,
      ConsentStatus status,
      ConsentSource source,
      Instant recordedAt,
      UUID recordedBy) {
    this.id = UUID.randomUUID();
    this.contactId = contactId;
    this.channel = channel;
    this.status = status;
    this.source = source;
    this.recordedAt = recordedAt;
    this.recordedBy = recordedBy;
  }

  public ConsentRecord toDomain() {
    return new ConsentRecord(id, contactId, channel, status, source, recordedAt, recordedBy);
  }
}
