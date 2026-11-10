package br.com.branlycrm.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.contact.domain.ConsentChannel;
import br.com.branlycrm.contact.domain.ConsentPolicy;
import br.com.branlycrm.contact.domain.ConsentRecord;
import br.com.branlycrm.contact.domain.ConsentSource;
import br.com.branlycrm.contact.domain.ConsentState;
import br.com.branlycrm.contact.domain.ConsentStatus;
import br.com.branlycrm.contact.domain.Contact;
import br.com.branlycrm.contact.domain.ContactCreatedVia;
import br.com.branlycrm.contact.domain.ContactFields;
import br.com.branlycrm.contact.domain.ContactSearch;
import br.com.branlycrm.contact.domain.PhoneNumber;
import br.com.branlycrm.contact.domain.SearchText;
import br.com.branlycrm.contact.domain.TagName;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class ContactDomainTest {
  private static final UUID CONTACT = UUID.randomUUID();
  private static final Instant T0 = Instant.parse("2026-11-09T12:00:00Z");

  @Test
  void searchTextIgnoresAccentsCaseAndSpacing() {
    assertThat(SearchText.normalize("  João   da SILVA Ção ")).isEqualTo("joao da silva cao");
    assertThat(SearchText.normalize("ÁÉÍÓÚ ãõ ü ñ")).isEqualTo("aeiou ao u n");
  }

  @Test
  void likePatternsTakeWildcardsLiterally() {
    assertThat(SearchText.contains("50%_off!")).isEqualTo("%50!%!_off!!%");
    assertThat(SearchText.startsWith("a_b")).isEqualTo("a!_b%");
  }

  @Test
  void contactFieldsAreTrimmedValidatedAndNeverPrinted() {
    var fields =
        new ContactFields(
            "  Maria Sintética ",
            PhoneNumber.parse("11987654321"),
            " maria@example.test ",
            LocalDate.of(1990, 5, 17),
            "   ");
    assertThat(fields.name()).isEqualTo("Maria Sintética");
    assertThat(fields.nameSearch()).isEqualTo("maria sintetica");
    assertThat(fields.email()).isEqualTo("maria@example.test");
    assertThat(fields.notes()).isNull();
    assertThat(fields.toString()).doesNotContain("Maria").doesNotContain("example.test");
    var contact =
        new Contact(UUID.randomUUID(), fields, ContactCreatedVia.MANUAL, T0, null, 0, null);
    assertThat(contact.toString()).doesNotContain("Maria").doesNotContain("98765");
  }

  @Test
  void contactFieldsRefuseInvalidValuesNamingOnlyTheField() {
    PhoneNumber phone = PhoneNumber.parse("11987654321");
    assertField(() -> new ContactFields(" ", phone, null, null, null), "name");
    assertField(() -> new ContactFields("a".repeat(151), phone, null, null, null), "name");
    assertField(() -> new ContactFields("Ana\u0000", phone, null, null, null), "name");
    assertField(() -> new ContactFields("Ana", null, null, null, null), "phone");
    assertField(() -> new ContactFields("Ana", phone, "sem-arroba", null, null), "email");
    assertField(
        () -> new ContactFields("Ana", phone, null, LocalDate.of(1899, 12, 31), null), "birthDate");
    assertField(
        () -> new ContactFields("Ana", phone, null, LocalDate.now().plusYears(1), null),
        "birthDate");
    assertField(() -> new ContactFields("Ana", phone, null, null, "x".repeat(2001)), "notes");
  }

  /** Non-ASCII spaces and invisible characters are refused before the database sees them. */
  @Test
  void contactFieldsRefuseUnicodeSpacesAndInvisibleCharacters() {
    PhoneNumber phone = PhoneNumber.parse("11987654321");
    for (String email : new String[] {"ana\u2003@example.test", "ana@example\u00A0test"}) {
      assertField(() -> new ContactFields("Ana", phone, email, null, null), "email");
    }
    for (String name : new String[] {"A\u202Ena", "A\u200Bna", "A\u0085na", "A\u2028na"}) {
      assertField(() -> new ContactFields(name, phone, null, null, null), "name");
    }
    assertField(() -> new ContactFields("Ana", phone, null, null, "nota\u0000"), "notes");
    assertThat(new ContactFields("Ana", phone, null, null, "linha 1\nlinha 2\tfim").notes())
        .contains("\n");
    assertField(() -> new TagName("V\u200BIP"), "name");
  }

  @Test
  void wholeNumberInAnyFormatIsSearchedExactly() {
    assertThat(new ContactSearch("(11) 8765-4321", null, false).exactPhone())
        .isEqualTo("+5511987654321");
    assertThat(new ContactSearch("Maria", null, false).exactPhone()).isNull();
  }

  @Test
  void searchTermNeedsTwoCharactersAndPhoneSearchThreeDigits() {
    assertThat(ContactSearch.all().term()).isNull();
    assertThat(new ContactSearch("  ", null, false).term()).isNull();
    assertField(() -> new ContactSearch("a", null, false), "q");
    assertThat(new ContactSearch("Jo", null, false).phoneDigits()).isNull();
    assertThat(new ContactSearch("(11) 98", null, false).phoneDigits()).isEqualTo("1198");
  }

  @Test
  void tagNamesAreTrimmedAndBounded() {
    assertThat(new TagName("  VIP ").value()).isEqualTo("VIP");
    assertField(() -> new TagName(" "), "name");
    assertField(() -> new TagName("x".repeat(51)), "name");
  }

  @Test
  void noRecordMeansNoneOnEveryChannel() {
    assertThat(ConsentPolicy.current(List.of()))
        .containsEntry(ConsentChannel.WHATSAPP, ConsentState.NONE)
        .containsEntry(ConsentChannel.EMAIL, ConsentState.NONE);
  }

  @Test
  void mostRecentRecordPrevailsPerChannel() {
    var history =
        List.of(
            record(ConsentChannel.WHATSAPP, ConsentStatus.OPT_IN, T0),
            record(ConsentChannel.WHATSAPP, ConsentStatus.OPT_OUT, T0.plusSeconds(60)),
            record(ConsentChannel.EMAIL, ConsentStatus.OPT_OUT, T0),
            record(ConsentChannel.EMAIL, ConsentStatus.OPT_IN, T0.plusSeconds(60)));
    assertThat(ConsentPolicy.current(history))
        .containsEntry(ConsentChannel.WHATSAPP, ConsentState.OPT_OUT)
        .containsEntry(ConsentChannel.EMAIL, ConsentState.OPT_IN);
  }

  @Test
  void optOutWinsATieWhateverTheOrder() {
    var in = record(ConsentChannel.WHATSAPP, ConsentStatus.OPT_IN, T0);
    var out = record(ConsentChannel.WHATSAPP, ConsentStatus.OPT_OUT, T0);
    assertThat(ConsentPolicy.current(List.of(in, out)).get(ConsentChannel.WHATSAPP))
        .isEqualTo(ConsentState.OPT_OUT);
    assertThat(ConsentPolicy.current(List.of(out, in)).get(ConsentChannel.WHATSAPP))
        .isEqualTo(ConsentState.OPT_OUT);
  }

  @Test
  void importNeverUndoesAnOptOut() {
    assertThat(
            ConsentPolicy.accepts(ConsentStatus.OPT_IN, ConsentSource.IMPORT, ConsentState.OPT_OUT))
        .isFalse();
    assertThat(
            ConsentPolicy.accepts(ConsentStatus.OPT_IN, ConsentSource.MANUAL, ConsentState.OPT_OUT))
        .isTrue();
    assertThat(
            ConsentPolicy.accepts(
                ConsentStatus.OPT_IN, ConsentSource.WHATSAPP, ConsentState.OPT_OUT))
        .isTrue();
    assertThat(ConsentPolicy.accepts(ConsentStatus.OPT_IN, ConsentSource.IMPORT, ConsentState.NONE))
        .isTrue();
    assertThat(
            ConsentPolicy.accepts(ConsentStatus.OPT_OUT, ConsentSource.IMPORT, ConsentState.OPT_IN))
        .isTrue();
  }

  private static ConsentRecord record(
      ConsentChannel channel, ConsentStatus status, Instant recordedAt) {
    return new ConsentRecord(
        UUID.randomUUID(), CONTACT, channel, status, ConsentSource.MANUAL, recordedAt, null);
  }

  private static void assertField(ThrowingCallable call, String field) {
    assertThatThrownBy(call)
        .isInstanceOf(InvalidInputException.class)
        .extracting(error -> ((InvalidInputException) error).fields())
        .isEqualTo(List.of(field));
  }
}
