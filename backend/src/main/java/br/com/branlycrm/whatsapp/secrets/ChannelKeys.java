package br.com.branlycrm.whatsapp.secrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the versioned channel key files written by infra/whatsapp/generate-keys.sh. Only regular
 * files of at most 16 KiB, RSA of at least 3072 bits; the key material never reaches logs.
 */
final class ChannelKeys {
  static final int MIN_BITS = 3072;
  private static final long MAX_BYTES = 16 * 1024;
  private static final Pattern PUBLIC =
      Pattern.compile("channel-key-v([1-9][0-9]{0,8})\\.pub\\.pem");
  private static final Pattern PRIVATE = Pattern.compile("channel-key-v([1-9][0-9]{0,8})\\.pem");

  private ChannelKeys() {}

  static Map<Integer, PublicKey> publicKeys(Path directory) throws IOException {
    Map<Integer, PublicKey> keys = new TreeMap<>();
    for (Map.Entry<Integer, byte[]> file : read(directory, PUBLIC, "PUBLIC KEY").entrySet()) {
      try {
        PublicKey key =
            KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(file.getValue()));
        keys.put(file.getKey(), strong(key));
      } catch (GeneralSecurityException invalid) {
        throw new IOException("Invalid channel public key version " + file.getKey());
      }
    }
    return keys;
  }

  static Map<Integer, PrivateKey> privateKeys(Path directory) throws IOException {
    Map<Integer, PrivateKey> keys = new TreeMap<>();
    for (Map.Entry<Integer, byte[]> file : read(directory, PRIVATE, "PRIVATE KEY").entrySet()) {
      try {
        PrivateKey key =
            KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(file.getValue()));
        keys.put(file.getKey(), strong(key));
      } catch (GeneralSecurityException invalid) {
        throw new IOException("Invalid channel private key version " + file.getKey());
      }
    }
    return keys;
  }

  private static <K extends java.security.Key> K strong(K key) throws GeneralSecurityException {
    if (!(key instanceof RSAKey rsa) || rsa.getModulus().bitLength() < MIN_BITS) {
      throw new GeneralSecurityException("Channel keys must be RSA with at least 3072 bits");
    }
    return key;
  }

  private static Map<Integer, byte[]> read(Path directory, Pattern names, String label)
      throws IOException {
    Map<Integer, byte[]> files = new TreeMap<>();
    if (directory == null || !Files.isDirectory(directory)) return files;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (Path file : entries) {
        Matcher name = names.matcher(file.getFileName().toString());
        if (!name.matches()) continue;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
          throw new IOException("Unsafe channel key file " + file.getFileName());
        }
        files.put(
            Integer.parseInt(name.group(1)),
            pem(Files.readString(file, StandardCharsets.US_ASCII), label));
      }
    }
    return files;
  }

  private static byte[] pem(String text, String label) throws IOException {
    String begin = "-----BEGIN " + label + "-----";
    String end = "-----END " + label + "-----";
    int start = text.indexOf(begin);
    int stop = text.indexOf(end);
    if (start < 0 || stop < start) throw new IOException("Not a PEM " + label);
    try {
      return Base64.getMimeDecoder().decode(text.substring(start + begin.length(), stop));
    } catch (IllegalArgumentException invalid) {
      throw new IOException("Invalid PEM " + label);
    }
  }
}
