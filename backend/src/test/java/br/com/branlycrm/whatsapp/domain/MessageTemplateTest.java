package br.com.branlycrm.whatsapp.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.whatsapp.domain.MessageTemplate.Button;
import br.com.branlycrm.whatsapp.domain.MessageTemplate.Component;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What the CRM sends of a Meta template, and Meta's rules for the values (CRM-085). */
class MessageTemplateTest {
  @Test
  void textTemplateIsSupportedAndRendered() {
    MessageTemplate template =
        MessageTemplate.of(
                "101",
                "retorno_consulta",
                "pt_BR",
                "utility",
                "approved",
                null,
                List.of(
                    new Component("HEADER", "TEXT", "Lembrete {{1}}", List.of()),
                    new Component("BODY", null, "Olá {{1}}, sua consulta é {{2}}.", List.of()),
                    new Component("FOOTER", null, "Clínica", List.of()),
                    new Component(
                        "BUTTONS",
                        null,
                        null,
                        List.of(
                            new Button("QUICK_REPLY", null),
                            new Button("URL", "https://example.test/agenda")))))
            .orElseThrow();
    assertThat(template.supported()).isTrue();
    assertThat(template.approved()).isTrue();
    assertThat(template.headerParams()).containsExactly("1");
    assertThat(template.bodyParams()).containsExactly("1", "2");
    assertThat(template.render(Map.of("1", "da semana"), Map.of("1", "Ana", "2", "amanhã")))
        .isEqualTo("Lembrete da semana\n\nOlá Ana, sua consulta é amanhã.\n\nClínica");
    assertThatThrownBy(() -> template.render(Map.of("1", "x"), Map.of("1", "Ana")))
        .hasMessage("body.2");
    assertThatThrownBy(
            () -> template.render(Map.of("1", "x"), Map.of("1", "Ana", "2", "a", "3", "b")))
        .hasMessage("body");
    String long1 = "a".repeat(600);
    assertThatThrownBy(() -> template.render(Map.of("1", "x"), Map.of("1", long1, "2", long1)))
        .hasMessage("body");
    assertThatThrownBy(
            () -> template.render(Map.of("1", "x"), Map.of("1", "Ana\nB", "2", "amanhã")))
        .hasMessage("body.1");
    assertThatThrownBy(
            () -> template.render(Map.of("1", "x"), Map.of("1", "A     B", "2", "amanhã")))
        .hasMessage("body.1");
  }

  @Test
  void namedParametersKeepTheirNames() {
    MessageTemplate template =
        MessageTemplate.of(
                "102",
                "promo",
                "pt_BR",
                "MARKETING",
                "APPROVED",
                "NAMED",
                List.of(new Component("BODY", null, "Oi {{nome}}, {{oferta}}!", List.of())))
            .orElseThrow();
    assertThat(template.named()).isTrue();
    assertThat(template.bodyParams()).containsExactly("nome", "oferta");
    assertThat(template.render(null, Map.of("nome", "Ana", "oferta", "volte logo")))
        .isEqualTo("Oi Ana, volte logo!");
  }

  @Test
  void unsupportedTemplatesAreKeptButMarked() {
    Component body = new Component("BODY", null, "Olá", List.of());
    assertThat(
            MessageTemplate.of(
                    "1",
                    "img",
                    "pt_BR",
                    "UTILITY",
                    "APPROVED",
                    null,
                    List.of(new Component("HEADER", "IMAGE", null, List.of()), body))
                .orElseThrow()
                .supported())
        .isFalse();
    assertThat(
            MessageTemplate.of(
                    "2",
                    "url",
                    "pt_BR",
                    "UTILITY",
                    "APPROVED",
                    null,
                    List.of(
                        body,
                        new Component(
                            "BUTTONS",
                            null,
                            null,
                            List.of(new Button("URL", "https://example.test/{{1}}")))))
                .orElseThrow()
                .supported())
        .isFalse();
    assertThat(
            MessageTemplate.of(
                    "3", "otp", "pt_BR", "AUTHENTICATION", "APPROVED", null, List.of(body))
                .orElseThrow()
                .supported())
        .isFalse();
    assertThat(
            MessageTemplate.of(
                    "4",
                    "gap",
                    "pt_BR",
                    "UTILITY",
                    "APPROVED",
                    null,
                    List.of(new Component("BODY", null, "{{2}} antes de {{1}}", List.of())))
                .orElseThrow()
                .supported())
        .isFalse();
    assertThat(
            MessageTemplate.of("6", "new", "pt_BR", "NEW_CATEGORY", "APPROVED", null, List.of(body))
                .orElseThrow()
                .supported())
        .isFalse();
    assertThat(MessageTemplate.of("x", "bad", "pt_BR", "UTILITY", "APPROVED", null, List.of(body)))
        .isEmpty();
    assertThat(MessageTemplate.of("5", "Bad Name", "pt_BR", "UTILITY", "APPROVED", null, List.of()))
        .isEmpty();
  }
}
