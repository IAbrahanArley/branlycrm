package br.com.branlycrm.whatsapp.repositories;

import br.com.branlycrm.tenant.domain.ChannelAccess;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import br.com.branlycrm.whatsapp.domain.MessageTemplate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The worker side of {@code whatsapp_templates} (CRM-085), in the channel's clinic database: the
 * copy of the account's templates, replaced in one transaction.
 */
@Repository
public class TemplateRepository {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final ClinicPersistence persistence;

  public TemplateRepository(ClinicPersistence persistence) {
    this.persistence = persistence;
  }

  /**
   * Replaces the copy: templates Meta no longer lists become DELETED (first, so one created again
   * with the same name and language fits), then each one is inserted or updated by Meta's id.
   *
   * @return how many templates are live after the copy
   */
  public int replace(
      ChannelAccess access, List<MessageTemplate> templates, List<String> keep, Instant at) {
    return persistence.inTransaction(
        ClinicTenant.ofChannel(access),
        session -> {
          String[] ids =
              java.util.stream.Stream.concat(
                      templates.stream().map(MessageTemplate::metaTemplateId), keep.stream())
                  .toArray(String[]::new);
          session
              .createNativeMutationQuery(
                  "UPDATE whatsapp_templates SET status = 'DELETED', supported = false,"
                      + " synced_at = :at, updated_at = :at"
                      + " WHERE status <> 'DELETED' AND NOT (meta_template_id = ANY(:ids))")
              .setParameter("at", OutboxRepository.utc(at))
              .setParameter("ids", ids, String[].class)
              .executeUpdate();
          for (MessageTemplate template : templates) {
            session
                .createNativeMutationQuery(
                    """
                    INSERT INTO whatsapp_templates (id, meta_template_id, name, language, category,
                        status, supported, parameter_format, header_text, body_text, footer_text,
                        header_params, body_params, synced_at, created_at, updated_at)
                    VALUES (:id, :meta, :name, :language, :category, :status, :supported, :format,
                        :header, :body, :footer, CAST(:headerParams AS jsonb),
                        CAST(:bodyParams AS jsonb), :at, :at, :at)
                    ON CONFLICT (meta_template_id) DO UPDATE SET
                        category = EXCLUDED.category, status = EXCLUDED.status,
                        supported = EXCLUDED.supported,
                        parameter_format = EXCLUDED.parameter_format,
                        header_text = EXCLUDED.header_text, body_text = EXCLUDED.body_text,
                        footer_text = EXCLUDED.footer_text,
                        header_params = EXCLUDED.header_params,
                        body_params = EXCLUDED.body_params, synced_at = EXCLUDED.synced_at,
                        updated_at = EXCLUDED.updated_at
                    """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("meta", template.metaTemplateId())
                .setParameter("name", template.name())
                .setParameter("language", template.language())
                .setParameter("category", template.category())
                .setParameter("status", template.status())
                .setParameter("supported", template.supported())
                .setParameter("format", template.named() ? "NAMED" : "POSITIONAL")
                .setParameter("header", template.headerText(), String.class)
                .setParameter("body", template.bodyText(), String.class)
                .setParameter("footer", template.footerText(), String.class)
                .setParameter("headerParams", JSON.writeValueAsString(template.headerParams()))
                .setParameter("bodyParams", JSON.writeValueAsString(template.bodyParams()))
                .setParameter("at", OutboxRepository.utc(at))
                .executeUpdate();
          }
          return templates.size();
        });
  }
}
