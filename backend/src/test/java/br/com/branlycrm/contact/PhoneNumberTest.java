package br.com.branlycrm.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.contact.domain.PhoneNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumberTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "(11) 98765-4321",
        "11 98765 4321",
        "11987654321",
        "+55 11 98765-4321",
        "+5511987654321",
        "5511987654321",
        "005511987654321",
        "011 98765-4321",
        "  +55 (11) 98765.4321  ",
        // Mobile written before the ninth digit, as WhatsApp may still report it.
        "(11) 8765-4321",
        "551187654321",
        "+55 11 8765-4321"
      })
  void everyFormatOfOneMobileBecomesTheSameNumber(String raw) {
    assertThat(PhoneNumber.parse(raw).e164()).isEqualTo("+5511987654321");
  }

  @ParameterizedTest
  @CsvSource({
    "(21) 3456-7890, +552134567890",
    "552134567890, +552134567890",
    "(55) 99876-5432, +5555998765432",
    "5555998765432, +5555998765432",
    "(55) 3222-1234, +555532221234",
    "+1 415 555 0100, +14155550100",
    "+351 912 345 678, +351912345678",
    "0044 20 7946 0958, +442079460958"
  })
  void landlinesAreaCode55AndOtherCountries(String raw, String expected) {
    assertThat(PhoneNumber.parse(raw).e164()).isEqualTo(expected);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "abc",
        "11 9876-54321x",
        "+55+11987654321",
        "0 21 11 98765-4321",
        "(01) 9876-5432",
        "(10) 98765-4321",
        "(11) 1234-5678",
        "(11) 88765-4321",
        "987654321",
        "+55 11 98765-43",
        "+1234567",
        "+1234567890123456",
        "+0 11 98765 4321",
        "1198765432100000000000000000000000"
      })
  void refusesAmbiguousOrInvalidNumbers(String raw) {
    assertThatThrownBy(() -> PhoneNumber.parse(raw)).isInstanceOf(InvalidInputException.class);
  }

  @Test
  void canonicalFormIsCheckedAlsoWhenBuiltDirectly() {
    assertThatThrownBy(() -> new PhoneNumber("+551187654321"))
        .isInstanceOf(InvalidInputException.class);
    assertThat(new PhoneNumber("+5511987654321").e164()).isEqualTo("+5511987654321");
  }

  @Test
  void neverPrintsTheNumber() {
    assertThat(PhoneNumber.parse("11987654321").toString()).doesNotContain("987654321");
  }
}
