package br.com.branlycrm.whatsapp.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sealing and opening of channel secrets with versioned key files (ADR-036). */
class ChannelSealingTest {
  @TempDir static Path keys;
  private static final String TOKEN = "synthetic-business-token-for-tests";

  @BeforeAll
  static void twoKeyVersions() throws Exception {
    Files.createDirectories(keys.resolve("public"));
    Files.createDirectories(keys.resolve("private"));
    write(1, 3072);
    write(2, 3072);
  }

  @Test
  void sealsWithTheNewestKeyAndOpensOnlyInTheSameContext() {
    var sealer = new ChannelSealer(keys.resolve("public").toString());
    var unsealer = new ChannelUnsealer(keys.resolve("private").toString());
    UUID channel = UUID.randomUUID();

    SealedSecret sealed = sealer.seal(channel, ChannelSecretPurpose.ACCESS_TOKEN, TOKEN);

    assertThat(sealed.keyVersion()).isEqualTo(2);
    assertThat(new String(sealed.value(), java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain(TOKEN);
    assertThat(sealed.toString()).doesNotContain(TOKEN);
    assertThat(unsealer.open(channel, ChannelSecretPurpose.ACCESS_TOKEN, sealed)).isEqualTo(TOKEN);
    // Another channel or another purpose cannot open it: the context is authenticated.
    assertInvalid(
        () -> unsealer.open(UUID.randomUUID(), ChannelSecretPurpose.ACCESS_TOKEN, sealed));
    assertInvalid(() -> unsealer.open(channel, ChannelSecretPurpose.REGISTRATION_PIN, sealed));
    // A single flipped byte is detected.
    byte[] tampered = sealed.value();
    tampered[tampered.length - 1] ^= 1;
    assertInvalid(
        () ->
            unsealer.open(
                channel, ChannelSecretPurpose.ACCESS_TOKEN, new SealedSecret(tampered, 2)));
    // Truncated or malformed values are refused without detail.
    byte[] truncated = java.util.Arrays.copyOf(sealed.value(), 20);
    assertInvalid(
        () ->
            unsealer.open(
                channel, ChannelSecretPurpose.ACCESS_TOKEN, new SealedSecret(truncated, 2)));
    assertInvalid(
        () ->
            unsealer.open(
                channel, ChannelSecretPurpose.ACCESS_TOKEN, new SealedSecret(new byte[] {9}, 2)));
    assertThat(unsealer.opens(sealer)).isTrue();
    // The stored version must match the header.
    assertInvalid(
        () ->
            unsealer.open(
                channel, ChannelSecretPurpose.ACCESS_TOKEN, new SealedSecret(sealed.value(), 1)));
  }

  @Test
  void opensValuesSealedWithAnOlderKeyAndRefusesMissingKeys() throws Exception {
    Path older = Files.createTempDirectory(keys, "older");
    Files.copy(
        keys.resolve("public/channel-key-v1.pub.pem"), older.resolve("channel-key-v1.pub.pem"));
    UUID channel = UUID.randomUUID();
    SealedSecret oldSeal =
        new ChannelSealer(older.toString())
            .seal(channel, ChannelSecretPurpose.REGISTRATION_PIN, "123456");

    assertThat(oldSeal.keyVersion()).isEqualTo(1);
    assertThat(
            new ChannelUnsealer(keys.resolve("private").toString())
                .open(channel, ChannelSecretPurpose.REGISTRATION_PIN, oldSeal))
        .isEqualTo("123456");

    var noKeys = new ChannelUnsealer("");
    assertThat(noKeys.configured()).isFalse();
    assertThatThrownBy(() -> noKeys.open(channel, ChannelSecretPurpose.REGISTRATION_PIN, oldSeal))
        .isInstanceOf(ChannelSecretException.class)
        .extracting(error -> ((ChannelSecretException) error).reason())
        .isEqualTo(ChannelSecretException.Reason.NOT_CONFIGURED);
    var noPublic = new ChannelSealer("");
    assertThatThrownBy(() -> noPublic.seal(channel, ChannelSecretPurpose.ACCESS_TOKEN, TOKEN))
        .isInstanceOf(ChannelSecretException.class);
  }

  @Test
  void refusesWeakKeys() throws Exception {
    Path weak = Files.createTempDirectory(keys, "weak");
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    Files.writeString(
        weak.resolve("channel-key-v1.pub.pem"), pem("PUBLIC KEY", pair.getPublic().getEncoded()));
    assertThatThrownBy(() -> new ChannelSealer(weak.toString()))
        .hasMessageContaining("Invalid channel public key");
  }

  private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(ChannelSecretException.class)
        .extracting(error -> ((ChannelSecretException) error).reason())
        .isEqualTo(ChannelSecretException.Reason.INVALID);
  }

  private static void write(int version, int bits) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(bits);
    KeyPair pair = generator.generateKeyPair();
    Files.writeString(
        keys.resolve("public/channel-key-v" + version + ".pub.pem"),
        pem("PUBLIC KEY", pair.getPublic().getEncoded()));
    Files.writeString(
        keys.resolve("private/channel-key-v" + version + ".pem"),
        pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
  }

  private static String pem(String label, byte[] der) {
    return "-----BEGIN "
        + label
        + "-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
        + "\n-----END "
        + label
        + "-----\n";
  }
}
