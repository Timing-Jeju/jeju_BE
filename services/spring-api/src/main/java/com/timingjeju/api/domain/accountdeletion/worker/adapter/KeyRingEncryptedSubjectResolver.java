package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import com.timingjeju.api.domain.accountdeletion.security.EncryptedSecret;
import com.timingjeju.api.domain.accountdeletion.security.SecretPurpose;
import com.timingjeju.api.domain.accountdeletion.security.VersionedAeadKeyRing;
import com.timingjeju.api.domain.accountdeletion.worker.AuthSubject;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import com.timingjeju.api.domain.accountdeletion.worker.EncryptedAuthSubject;
import com.timingjeju.api.domain.accountdeletion.worker.EncryptedSubjectResolver;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public final class KeyRingEncryptedSubjectResolver implements EncryptedSubjectResolver {

  private final VersionedAeadKeyRing keyRing;

  public KeyRingEncryptedSubjectResolver(VersionedAeadKeyRing keyRing) {
    this.keyRing = Objects.requireNonNull(keyRing, "keyRing은 필수입니다.");
  }

  @Override
  public AuthSubject resolve(String requestId, EncryptedAuthSubject encryptedSubject) {
    byte[] plaintext = null;
    try {
      plaintext =
          keyRing.decrypt(
              requestId,
              SecretPurpose.AUTH_SUBJECT,
              new EncryptedSecret(encryptedSubject.ciphertext(), encryptedSubject.keyVersion()));
      return AuthSubject.of(new String(plaintext, StandardCharsets.US_ASCII));
    } catch (RuntimeException failure) {
      throw DeletionOperationException.terminal("SUBJECT_DECRYPTION_FAILED");
    } finally {
      if (plaintext != null) {
        Arrays.fill(plaintext, (byte) 0);
      }
    }
  }
}
