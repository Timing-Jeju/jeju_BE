package com.timingjeju.api.domain.accountdeletion.security;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AesGcmVersionedKeyRing implements VersionedAeadKeyRing {
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private final String activeVersion;
  private final Map<String, SecretKey> keys;
  private final SecureRandom random;

  public AesGcmVersionedKeyRing(
      String activeVersion, Map<String, SecretKey> keys, SecureRandom random) {
    if (activeVersion == null || !keys.containsKey(activeVersion)) {
      throw new IllegalArgumentException("active encryption key version이 없습니다.");
    }
    if (keys.isEmpty()
        || keys.values().stream()
            .anyMatch(key -> key.getEncoded() == null || key.getEncoded().length != 32)) {
      throw new IllegalArgumentException("account deletion AEAD key는 256-bit 이상이어야 합니다.");
    }
    this.activeVersion = activeVersion;
    this.keys = Map.copyOf(keys);
    this.random = random;
  }

  @Override
  public EncryptedSecret encrypt(SecretPurpose purpose, byte[] plaintext) {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, keys.get(activeVersion), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(purpose.aad());
      byte[] encrypted = cipher.doFinal(plaintext);
      byte[] envelope = new byte[nonce.length + encrypted.length];
      System.arraycopy(nonce, 0, envelope, 0, nonce.length);
      System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
      return new EncryptedSecret(
          Base64.getUrlEncoder().withoutPadding().encodeToString(envelope), activeVersion);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("account deletion secret encryption failed", exception);
    }
  }

  @Override
  public byte[] decrypt(SecretPurpose purpose, EncryptedSecret secret) {
    SecretKey key = keys.get(secret.keyVersion());
    if (key == null) throw new SecretDecryptionException();
    try {
      byte[] envelope = Base64.getUrlDecoder().decode(secret.ciphertext());
      if (envelope.length <= NONCE_BYTES) throw new SecretDecryptionException();
      byte[] nonce = java.util.Arrays.copyOfRange(envelope, 0, NONCE_BYTES);
      byte[] ciphertext = java.util.Arrays.copyOfRange(envelope, NONCE_BYTES, envelope.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(purpose.aad());
      return cipher.doFinal(ciphertext);
    } catch (IllegalArgumentException | GeneralSecurityException exception) {
      throw new SecretDecryptionException();
    }
  }
}
