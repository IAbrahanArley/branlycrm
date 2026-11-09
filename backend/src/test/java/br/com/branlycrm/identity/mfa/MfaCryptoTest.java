package br.com.branlycrm.identity.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.identity.mfa.crypto.MfaCrypto;
import br.com.branlycrm.identity.mfa.domain.SealedSecret;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MfaCryptoTest {
  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
  private final MfaCrypto crypto = MfaCrypto.fromBase64(KEY);

  @Test
  void refusesMissingOrMalformedKeysWithoutEchoingThem() {
    for (String key :
        new String[] {
          null,
          "",
          "  ",
          "not base64 !!",
          Base64.getEncoder().encodeToString(new byte[16]),
          Base64.getEncoder().encodeToString(new byte[33])
        }) {
      var refused =
          assertThatThrownBy(() -> MfaCrypto.fromBase64(key))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("MFA_ENCRYPTION_KEY");
      if (key != null && !key.isBlank()) refused.message().doesNotContain(key);
    }
    assertThat(crypto.toString()).doesNotContain(KEY);
  }

  @Test
  void sealsWithAuthenticatedDataBoundToTheSubject() {
    UUID subject = UUID.randomUUID();
    byte[] secret = crypto.newSecret();
    assertThat(secret).hasSize(20);
    SealedSecret sealed = crypto.seal(subject, secret);
    assertThat(sealed.ciphertext()).hasSize(36).isNotEqualTo(secret);
    assertThat(sealed.nonce()).hasSize(12);
    assertThat(crypto.open(subject, sealed)).isEqualTo(secret);
    assertThat(crypto.seal(subject, secret).nonce()).isNotEqualTo(sealed.nonce());
    assertThat(sealed.toString()).doesNotContain(Base64.getEncoder().encodeToString(secret));

    // Another subject, a flipped byte, another nonce, another key id or another key all fail.
    assertThatThrownBy(() -> crypto.open(UUID.randomUUID(), sealed))
        .isInstanceOf(IllegalStateException.class);
    byte[] tampered = sealed.ciphertext();
    tampered[0] ^= 1;
    assertThatThrownBy(() -> crypto.open(subject, new SealedSecret(1, tampered, sealed.nonce())))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> crypto.open(subject, new SealedSecret(1, sealed.ciphertext(), new byte[12])))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> crypto.open(subject, new SealedSecret(2, sealed.ciphertext(), sealed.nonce())))
        .isInstanceOf(IllegalStateException.class);
    byte[] otherKey = new byte[32];
    otherKey[0] = 1;
    MfaCrypto other = MfaCrypto.fromBase64(Base64.getEncoder().encodeToString(otherKey));
    assertThatThrownBy(() -> other.open(subject, sealed)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void recoveryCodesCarryEightyBitsAndAreHashedPerSubject() {
    List<String> codes = crypto.newRecoveryCodes();
    assertThat(codes).hasSize(10);
    assertThat(new HashSet<>(codes)).hasSize(10);
    for (String code : codes) {
      assertThat(code).matches("[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}");
    }
    UUID subject = UUID.randomUUID();
    String code = codes.get(0);
    byte[] hash = crypto.hashRecoveryCode(subject, code);
    assertThat(hash).hasSize(32);
    assertThat(crypto.hashRecoveryCode(subject, code.toLowerCase(Locale.ROOT).replace("-", " ")))
        .as("typing variants normalize to the same code")
        .isEqualTo(hash);
    assertThat(crypto.hashRecoveryCode(UUID.randomUUID(), code)).isNotEqualTo(hash);
    assertThat(MfaCrypto.normalizeRecoveryCode("ABCD-EFGH")).isNull();
    assertThat(MfaCrypto.normalizeRecoveryCode("ABCD-EFGH-IJKL-MN01")).isNull();
    assertThat(MfaCrypto.normalizeRecoveryCode(null)).isNull();
  }
}
