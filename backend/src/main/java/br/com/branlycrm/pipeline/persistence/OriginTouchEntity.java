package br.com.branlycrm.pipeline.persistence;

import br.com.branlycrm.pipeline.domain.LeadOrigin;
import br.com.branlycrm.pipeline.domain.OriginTouch;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A row of origin_touches (tenant migration V10), never updated. */
@Entity
@Table(name = "origin_touches")
@Immutable
public class OriginTouchEntity {
  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "contact_id", nullable = false)
  private UUID contactId;

  @Column(name = "lead_id")
  private UUID leadId;

  @Enumerated(EnumType.STRING)
  @Column(name = "origin_type", nullable = false, length = 20)
  private LeadOrigin.Type type;

  @Enumerated(EnumType.STRING)
  @Column(name = "origin_source", nullable = false, length = 8)
  private LeadOrigin.Source source;

  @Column(name = "ad_id", length = 64)
  private String adId;

  @Column(name = "code", length = 32)
  private String code;

  @Column(name = "title", length = 200)
  private String title;

  @Column(name = "ctwa_clid", length = 512)
  private String ctwaClid;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  @Column(name = "seq", insertable = false, updatable = false)
  private Long seq;

  protected OriginTouchEntity() {}

  public OriginTouchEntity(UUID contactId, UUID leadId, LeadOrigin origin, Instant receivedAt) {
    this.id = UUID.randomUUID();
    this.contactId = contactId;
    this.leadId = leadId;
    this.type = origin.type();
    this.source = origin.source();
    this.adId = origin.adId();
    this.code = origin.code();
    this.title = origin.title();
    this.ctwaClid = origin.ctwaClid();
    this.receivedAt = receivedAt;
  }

  public OriginTouch toDomain() {
    return new OriginTouch(
        id,
        contactId,
        leadId,
        new LeadOrigin(type, source, adId, code, title, ctwaClid),
        receivedAt);
  }
}
