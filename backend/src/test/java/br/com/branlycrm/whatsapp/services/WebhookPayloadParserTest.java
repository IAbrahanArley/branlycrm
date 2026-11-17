package br.com.branlycrm.whatsapp.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.whatsapp.domain.InboundKind;
import br.com.branlycrm.whatsapp.domain.RoutedEvent;
import br.com.branlycrm.whatsapp.domain.WebhookSignature;
import br.com.branlycrm.whatsapp.exceptions.WebhookException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Splitting and deduplication of Meta deliveries, with synthetic payloads only. */
class WebhookPayloadParserTest {
  private final WebhookPayloadParser parser =
      new WebhookPayloadParser(JsonMapper.builder().build());

  @Test
  void splitsMessagesStatusesAndAccountEventsWithStableKeys() {
    String body =
        """
        {"object":"whatsapp_business_account","entry":[
          {"id":"1001","changes":[
            {"field":"messages","value":{"messaging_product":"whatsapp",
              "metadata":{"display_phone_number":"5500000000000","phone_number_id":"2001"},
              "contacts":[{"wa_id":"5500000000001","profile":{"name":"Sintético"}}],
              "messages":[{"from":"5500000000001","id":"wamid.A","timestamp":"1763380000",
                           "type":"text","text":{"body":"Olá"}},
                          {"from":"5500000000001","timestamp":"1763380001","type":"text"}],
              "statuses":[{"id":"wamid.B","status":"delivered","timestamp":"1763380002"}]}},
            {"field":"account_update","value":{"event":"VERIFIED_ACCOUNT"}},
            {"field":"history","value":{"metadata":{"phone_number_id":"2001"}}}
          ]},
          {"id":"not-a-number","changes":[]}
        ]}
        """;
    var parsed = parser.parse(bytes(body));

    assertThat(parsed.events()).hasSize(3);
    // One message without id, the history import (refused) and the malformed entry.
    assertThat(parsed.skipped()).isEqualTo(3);
    RoutedEvent message = parsed.events().get(0);
    assertThat(message.event().kind()).isEqualTo(InboundKind.MESSAGE);
    assertThat(message.route().phoneNumberId()).isEqualTo("2001");
    assertThat(message.route().wabaId()).isEqualTo("1001");
    assertThat(message.event().wamid()).isEqualTo("wamid.A");
    assertThat(message.event().eventAt()).isEqualTo(Instant.ofEpochSecond(1763380000));
    assertThat(message.event().dedupKey())
        .isEqualTo("m:" + WebhookPayloadParser.sha256("wamid.A"))
        .doesNotContain("wamid");
    assertThat(message.event().payload()).contains("\"message\"").contains("\"contacts\"");
    assertThat(message.event().toString()).doesNotContain("Olá").doesNotContain("55000");

    RoutedEvent status = parsed.events().get(1);
    assertThat(status.event().kind()).isEqualTo(InboundKind.STATUS);
    assertThat(status.event().dedupKey())
        .isEqualTo("s:" + WebhookPayloadParser.sha256("wamid.B\ndelivered"));

    RoutedEvent account = parsed.events().get(2);
    assertThat(account.event().kind()).isEqualTo(InboundKind.ACCOUNT);
    assertThat(account.route().byNumber()).isFalse();
    assertThat(account.event().dedupKey()).startsWith("h:");

    // The same delivery gives the same keys; another status of the same message does not.
    assertThat(parser.parse(bytes(body)).events().get(2).event().dedupKey())
        .isEqualTo(account.event().dedupKey());
    String read = body.replace("\"delivered\"", "\"read\"");
    assertThat(parser.parse(bytes(read)).events().get(1).event().dedupKey())
        .isNotEqualTo(status.event().dedupKey());
  }

  @Test
  void accountEventsRepeatAcrossEntriesAndNulIsDropped() {
    String change = "{\"field\":\"account_update\",\"value\":{\"event\":\"FLAGGED\"}}";
    String first =
        "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1001\","
            + "\"time\":100,\"changes\":["
            + change
            + "]}]}";
    String later = first.replace("\"time\":100", "\"time\":200");
    String key = parser.parse(bytes(first)).events().get(0).event().dedupKey();
    assertThat(parser.parse(bytes(first)).events().get(0).event().dedupKey()).isEqualTo(key);
    assertThat(parser.parse(bytes(later)).events().get(0).event().dedupKey()).isNotEqualTo(key);

    // A literal backslash-u text stays; the NUL character itself is removed.
    String nul =
        "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1001\",\"changes\":["
            + "{\"field\":\"messages\",\"value\":{\"metadata\":{\"phone_number_id\":\"2001\"},"
            + "\"messages\":[{\"id\":\"wamid.N\",\"text\":{\"body\":\"a\\u0000b \\\\u0000\"}}]}}]}]}";
    String payload = parser.parse(bytes(nul)).events().get(0).event().payload();
    assertThat(payload).contains("\"ab \\\\u0000\"").doesNotContain("a\\u0000b");
  }

  @Test
  void ignoresOtherObjectsAndRefusesInvalidJson() {
    assertThat(parser.parse(bytes("{\"object\":\"page\",\"entry\":[]}")).events()).isEmpty();
    assertThatThrownBy(() -> parser.parse(bytes("not json")))
        .isInstanceOfSatisfying(
            WebhookException.class,
            error -> assertThat(error.reason()).isEqualTo(WebhookException.Reason.MALFORMED));
    assertThatThrownBy(() -> parser.parse(bytes("[1]"))).isInstanceOf(WebhookException.class);
  }

  @Test
  void signatureIsCheckedOnTheRawBytes() {
    byte[] secret = bytes("synthetic-app-secret-0001");
    byte[] body = bytes("{\"object\":\"whatsapp_business_account\"}");
    String header = WebhookSignature.sign(secret, body);

    assertThat(WebhookSignature.matches(secret, body, header)).isTrue();
    assertThat(WebhookSignature.matches(secret, bytes("{\"object\":\"x\"}"), header)).isFalse();
    assertThat(WebhookSignature.matches(bytes("other-secret-000000"), body, header)).isFalse();
    assertThat(WebhookSignature.matches(secret, body, null)).isFalse();
    assertThat(WebhookSignature.matches(secret, body, header.substring(7))).isFalse();
    assertThat(WebhookSignature.matches(secret, body, "sha256=" + "zz".repeat(32))).isFalse();
    assertThat(WebhookSignature.matches(new byte[0], body, header)).isFalse();
    assertThat(WebhookSignature.sameText("token-0000000000", "token-0000000000")).isTrue();
    assertThat(WebhookSignature.sameText("token-0000000000", "token-0000000001")).isFalse();
    assertThat(WebhookSignature.sameText("", "")).isFalse();
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
