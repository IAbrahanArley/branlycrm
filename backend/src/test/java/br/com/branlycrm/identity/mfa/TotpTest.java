package br.com.branlycrm.identity.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.identity.mfa.crypto.Base32;
import br.com.branlycrm.identity.mfa.crypto.Totp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TotpTest {
  /** RFC 6238 Appendix B, SHA-1 seed. */
  private static final byte[] RFC_SECRET =
      "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

  /** The RFC lists 8 digits; 6 digits are the last six of the same truncated value. */
  @ParameterizedTest
  @CsvSource({
    "59, 94287082",
    "1111111109, 07081804",
    "1111111111, 14050471",
    "1234567890, 89005924",
    "2000000000, 69279037",
    "20000000000, 65353130"
  })
  void matchesTheRfc6238TestVectors(long epochSeconds, String eightDigits) {
    long step = Totp.step(Instant.ofEpochSecond(epochSeconds));
    assertThat(Totp.code(RFC_SECRET, step)).isEqualTo(eightDigits.substring(2));
  }

  @Test
  void acceptsOneStepOfDriftEachWayAndNothingBeyond() {
    long now = Totp.step(Instant.ofEpochSecond(1_234_567_890));
    for (long drift = -1; drift <= 1; drift++) {
      assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now + drift), now))
          .hasValue(now + drift);
    }
    assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now - 2), now)).isEmpty();
    assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now + 2), now)).isEmpty();
    assertThat(Totp.match(RFC_SECRET, "abcdef", now)).isEmpty();
  }

  @Test
  void base32FollowsRfc4648WithoutPadding() {
    // RFC 4648, section 10, with the padding removed.
    String[][] vectors = {
      {"", ""},
      {"f", "MY"},
      {"fo", "MZXQ"},
      {"foo", "MZXW6"},
      {"foob", "MZXW6YQ"},
      {"fooba", "MZXW6YTB"},
      {"foobar", "MZXW6YTBOI"}
    };
    for (String[] vector : vectors) {
      byte[] raw = vector[0].getBytes(StandardCharsets.US_ASCII);
      assertThat(Base32.encode(raw)).isEqualTo(vector[1]);
      assertThat(Base32.decode(vector[1])).isEqualTo(raw);
    }
    assertThat(Base32.encode(RFC_SECRET)).hasSize(32);
    assertThatThrownBy(() -> Base32.decode("MZ1")).isInstanceOf(IllegalArgumentException.class);
  }
}
