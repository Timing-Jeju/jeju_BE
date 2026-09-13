package com.timingjeju.api.domain.accountdeletion.security;

public interface VersionedAeadKeyRing {
  default EncryptedSecret encrypt(SecretPurpose purpose, byte[] plaintext) {
    throw new SecretDecryptionException();
  }

  default byte[] decrypt(SecretPurpose purpose, EncryptedSecret secret) {
    throw new SecretDecryptionException();
  }

  default EncryptedSecret encrypt(String requestId, SecretPurpose purpose, byte[] plaintext) {
    return encrypt(purpose, plaintext);
  }

  default byte[] decrypt(String requestId, SecretPurpose purpose, EncryptedSecret secret) {
    return decrypt(purpose, secret);
  }
}
