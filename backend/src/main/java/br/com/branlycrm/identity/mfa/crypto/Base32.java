package br.com.branlycrm.identity.mfa.crypto;

/** RFC 4648 base32, upper case and without padding, as authenticator apps expect. */
public final class Base32 {
  static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  private Base32() {}

  public static String encode(byte[] data) {
    StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
    int buffer = 0;
    int bits = 0;
    for (byte value : data) {
      buffer = (buffer << 8) | (value & 0xFF);
      bits += 8;
      while (bits >= 5) {
        out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
        bits -= 5;
      }
    }
    if (bits > 0) out.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
    return out.toString();
  }

  /**
   * @throws IllegalArgumentException for characters outside the alphabet; never echoes the input.
   */
  public static byte[] decode(String text) {
    byte[] out = new byte[text.length() * 5 / 8];
    int buffer = 0;
    int bits = 0;
    int index = 0;
    for (int i = 0; i < text.length(); i++) {
      int value = ALPHABET.indexOf(text.charAt(i));
      if (value < 0) throw new IllegalArgumentException("Not base32");
      buffer = (buffer << 5) | value;
      bits += 5;
      if (bits >= 8) {
        out[index++] = (byte) (buffer >> (bits - 8));
        bits -= 8;
      }
    }
    return out;
  }

  static boolean isAlphabet(char value) {
    return ALPHABET.indexOf(value) >= 0;
  }
}
