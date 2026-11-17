package br.com.branlycrm.whatsapp.secrets;

import java.util.Arrays;

/** A sealed channel secret and the key version that opens it; safe to store, never to log. */
public record SealedSecret(byte[] value, int keyVersion) {
  public SealedSecret {
    if (value == null || value.length == 0 || keyVersion <= 0) {
      throw new IllegalArgumentException("Incomplete sealed secret");
    }
    value = value.clone();
  }

  @Override
  public byte[] value() {
    return value.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SealedSecret that
        && keyVersion == that.keyVersion
        && Arrays.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(value) + keyVersion;
  }

  @Override
  public String toString() {
    return "SealedSecret[keyVersion=" + keyVersion + "]";
  }
}
