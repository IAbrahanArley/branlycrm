package br.com.branlycrm.whatsapp.services;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.pipeline.domain.LeadOrigin;
import br.com.branlycrm.whatsapp.domain.InboundAction;
import br.com.branlycrm.whatsapp.domain.InboundKind;
import br.com.branlycrm.whatsapp.domain.InboundMessage;
import br.com.branlycrm.whatsapp.domain.OriginDetector;
import br.com.branlycrm.whatsapp.domain.Referral;
import br.com.branlycrm.whatsapp.domain.StoredEvent;
import br.com.branlycrm.whatsapp.repositories.InboundQueueRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Reading stored events and detecting origins (CRM-082b, CRM-091), with synthetic data only. */
class InboundEventInterpreterTest {
  private final InboundEventInterpreter interpreter =
      new InboundEventInterpreter(JsonMapper.builder().build());

  @Test
  void textMessageBecomesContactLeadOriginAndMessage() {
    InboundMessage message =
        store(
            "messages",
            "{\"from\":\"558399001234\",\"id\":\"wamid.1\",\"timestamp\":\"1763380000\","
                + "\"type\":\"text\",\"text\":{\"body\":\"[IG01] Olá\\u0000!\"}}",
            "[{\"wa_id\":\"558399001234\",\"profile\":{\"name\":\" Paciente\\u200b Sintético \"}}]");

    // The old 8-digit mobile gains the ninth digit (ADR-032).
    assertThat(message.contact().phone().e164()).isEqualTo("+5583999001234");
    assertThat(message.contact().name()).isEqualTo("Paciente Sintético");
    assertThat(message.fromBusinessApp()).isFalse();
    assertThat(message.body()).isEqualTo("[IG01] Olá!");
    assertThat(message.sentAt()).isEqualTo(Instant.ofEpochSecond(1763380000));
    assertThat(message.origin().source()).isEqualTo(LeadOrigin.Source.CODE);
    assertThat(message.origin().code()).isEqualTo("IG01");
    assertThat(message.origin().type()).isEqualTo(LeadOrigin.Type.OTHER);
    assertThat(message.toString()).doesNotContain("Olá").doesNotContain("5583");
  }

  @Test
  void mediaEchoesStatusesAndInvalidNumbers() {
    InboundMessage image =
        store(
            "messages",
            "{\"from\":\"5511988887777\",\"id\":\"wamid.2\",\"type\":\"image\",\"image\":{"
                + "\"id\":\"media-1\",\"mime_type\":\"image/jpeg\",\"caption\":\"foto\"}}",
            "[]");
    assertThat(image.type()).isEqualTo("image");
    assertThat(image.mediaId()).isEqualTo("media-1");
    assertThat(image.mediaMime()).isEqualTo("image/jpeg");
    assertThat(image.body()).isEqualTo("foto");
    // Without a profile name, the contact is named by its number.
    assertThat(image.contact().name()).isEqualTo("+5511988887777");

    InboundMessage echo =
        store(
            "smb_message_echoes",
            "{\"from\":\"5500000000000\",\"to\":\"5511988887777\",\"id\":\"wamid.3\","
                + "\"type\":\"text\",\"text\":{\"body\":\"[IG01] retorno\"}}",
            "[]");
    assertThat(echo.fromBusinessApp()).isTrue();
    assertThat(echo.contact().phone().e164()).isEqualTo("+5511988887777");
    assertThat(echo.origin()).isEqualTo(LeadOrigin.unidentified());

    assertThat(
            interpreter.decide(
                new StoredEvent(
                    UUID.randomUUID(), InboundKind.STATUS, "messages", null, Instant.now(), "{}")))
        .isEqualTo(new InboundAction.Skip(InboundEventInterpreter.INVALID_PAYLOAD));
    // A status the CRM does not track is kept with a code; a delivery status is reconciled.
    assertThat(interpreter.decide(status("deleted", null)))
        .isEqualTo(new InboundAction.Skip(InboundEventInterpreter.DEFERRED_STATUS));
    UUID outbox = UUID.randomUUID();
    InboundAction delivered = interpreter.decide(status("delivered", outbox.toString()));
    assertThat(delivered).isInstanceOf(InboundAction.Status.class);
    assertThat(((InboundAction.Status) delivered).outboxId()).isEqualTo(outbox);
    assertThat(((InboundAction.Status) delivered).wamid()).isEqualTo("wamid.S");
    InboundAction notOurs = interpreter.decide(status("read", "campaign-7"));
    assertThat(((InboundAction.Status) notOurs).outboxId()).isNull();
    InboundAction failed =
        interpreter.decide(
            new StoredEvent(
                UUID.randomUUID(),
                InboundKind.STATUS,
                "messages",
                null,
                Instant.now(),
                "{\"status\":{\"id\":\"wamid.S\",\"status\":\"failed\","
                    + "\"errors\":[{\"code\":131047}]}}"));
    assertThat(((InboundAction.Status) failed).errorCode()).isEqualTo("META_131047");
    assertThat(
            interpreter.decide(
                new StoredEvent(
                    UUID.randomUUID(),
                    InboundKind.ACCOUNT,
                    "account_update",
                    null,
                    Instant.now(),
                    "{}")))
        .isEqualTo(new InboundAction.Skip(InboundEventInterpreter.DEFERRED_ACCOUNT));
    assertThat(
            interpreter.decide(
                event("messages", "{\"from\":\"12\",\"id\":\"wamid.4\",\"type\":\"text\"}", "[]")))
        .isEqualTo(new InboundAction.Skip(InboundEventInterpreter.INVALID_PHONE));
    assertThat(
            interpreter.decide(
                new StoredEvent(
                    UUID.randomUUID(),
                    InboundKind.MESSAGE,
                    "messages",
                    null,
                    Instant.now(),
                    "not json")))
        .isEqualTo(new InboundAction.Skip(InboundEventInterpreter.INVALID_PAYLOAD));
  }

  @Test
  void originFromAdsPostsCodesOrNothing() {
    LeadOrigin ad =
        OriginDetector.detect(
            new Referral("ad", "120200000000001", "Clareamento em promoção", "clid-1"), "[IG01]");
    assertThat(ad.type()).isEqualTo(LeadOrigin.Type.PAID_TRAFFIC);
    assertThat(ad.source()).isEqualTo(LeadOrigin.Source.AD);
    assertThat(ad.adId()).isEqualTo("120200000000001");
    assertThat(ad.title()).isEqualTo("Clareamento em promoção");
    assertThat(ad.ctwaClid()).isEqualTo("clid-1");

    LeadOrigin post = OriginDetector.detect(new Referral("post", "p-1", null, null), null);
    assertThat(post.type()).isEqualTo(LeadOrigin.Type.PAID_TRAFFIC);

    LeadOrigin longTitle =
        OriginDetector.detect(new Referral("ad", "id ç", "x".repeat(300), null), null);
    assertThat(longTitle.adId()).isNull();
    assertThat(longTitle.title()).hasSize(LeadOrigin.TITLE_MAX);

    assertThat(OriginDetector.detect(null, "  [BLACK-FRIDAY_1] quero").code())
        .isEqualTo("BLACK-FRIDAY_1");
    assertThat(OriginDetector.detect(null, "[có digo] oi")).isEqualTo(LeadOrigin.unidentified());
    assertThat(OriginDetector.detect(null, "oi [IG01]")).isEqualTo(LeadOrigin.unidentified());
    assertThat(OriginDetector.detect(null, null)).isEqualTo(LeadOrigin.unidentified());
    assertThat(OriginDetector.detect(new Referral("other", null, null, null), "oi"))
        .isEqualTo(LeadOrigin.unidentified());
  }

  @Test
  void retriesWaitLongerUpToAnHour() {
    assertThat(InboundQueueRepository.delay(1)).isEqualTo(Duration.ofSeconds(30));
    assertThat(InboundQueueRepository.delay(2)).isEqualTo(Duration.ofMinutes(1));
    assertThat(InboundQueueRepository.delay(8)).isEqualTo(Duration.ofHours(1));
  }

  private InboundMessage store(String field, String message, String contacts) {
    InboundAction action = interpreter.decide(event(field, message, contacts));
    assertThat(action).isInstanceOf(InboundAction.Store.class);
    return ((InboundAction.Store) action).message();
  }

  private static StoredEvent status(String value, String callback) {
    return new StoredEvent(
        UUID.randomUUID(),
        InboundKind.STATUS,
        "messages",
        null,
        Instant.now(),
        "{\"status\":{\"id\":\"wamid.S\",\"status\":\""
            + value
            + "\",\"timestamp\":\"1763380001\""
            + (callback == null ? "" : ",\"biz_opaque_callback_data\":\"" + callback + "\"")
            + "}}");
  }

  private static StoredEvent event(String field, String message, String contacts) {
    String payload =
        "{\"field\":\""
            + field
            + "\",\"metadata\":{\"phone_number_id\":\"2001\"},\"contacts\":"
            + contacts
            + ",\"message\":"
            + message
            + "}";
    return new StoredEvent(
        UUID.randomUUID(), InboundKind.MESSAGE, field, null, Instant.now(), payload);
  }
}
