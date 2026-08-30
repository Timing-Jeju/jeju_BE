package com.timingjeju.api.global.notification;

import com.timingjeju.api.application.notification.ProtectedRegistrationToken;
import com.timingjeju.api.application.notification.RegistrationTokenProtectionFailure;
import com.timingjeju.api.application.notification.RegistrationTokenProtector;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AesGcmRegistrationTokenProtector implements RegistrationTokenProtector {

  private static final byte VERSION = 1;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private final SecretKey key;
  private final SecureRandom random;

  public AesGcmRegistrationTokenProtector(SecretKey key, SecureRandom random) {
    this.key = Objects.requireNonNull(key);
    this.random = Objects.requireNonNull(random);
  }

  @Override
  public ProtectedRegistrationToken protect(String registrationToken) {
    try {
      byte[] plaintext = registrationToken.getBytes(StandardCharsets.UTF_8);
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] encrypted = cipher.doFinal(plaintext);
      ByteBuffer envelope = ByteBuffer.allocate(1 + iv.length + encrypted.length);
      envelope.put(VERSION).put(iv).put(encrypted);
      return new ProtectedRegistrationToken(
          Base64.getUrlEncoder().withoutPadding().encodeToString(envelope.array()),
          MessageDigest.getInstance("SHA-256").digest(plaintext));
    } catch (GeneralSecurityException | RuntimeException failure) {
      throw new RegistrationTokenProtectionFailure();
    }
  }

  @Override
  public String reveal(String ciphertext) {
    try {
      byte[] envelope = Base64.getUrlDecoder().decode(ciphertext);
      if (envelope.length <= 1 + IV_BYTES || envelope[0] != VERSION) {
        throw new GeneralSecurityException();
      }
      byte[] iv = java.util.Arrays.copyOfRange(envelope, 1, 1 + IV_BYTES);
      byte[] encrypted = java.util.Arrays.copyOfRange(envelope, 1 + IV_BYTES, envelope.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | RuntimeException failure) {
      throw new RegistrationTokenProtectionFailure();
    }
  }
}
