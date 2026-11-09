package br.com.branlycrm.identity.mfa.domain;

/**
 * A TOTP secret encrypted with AES-256-GCM: the encryption key id, the 12-byte nonce and the
 * ciphertext with its tag. Opening it requires the subject it was sealed for (AAD).
 */
public record SealedSecret(int keyId, byte[] ciphertext, byte[] nonce) {
  public static final int NONCE_BYTES = 12;
  public static final int CIPHERTEXT_BYTES = 36;

  public SealedSecret {
    if (keyId <= 0
        || ciphertext == null
        || ciphertext.length != CIPHERTEXT_BYTES
        || nonce == null
        || nonce.length != NONCE_BYTES) {
      throw new IllegalArgumentException("Malformed sealed secret");
    }
    ciphertext = ciphertext.clone();
    nonce = nonce.clone();
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }

  @Override
  public byte[] nonce() {
    return nonce.clone();
  }

  @Override
  public String toString() {
    return "SealedSecret[keyId=" + keyId + "]";
  }
}
