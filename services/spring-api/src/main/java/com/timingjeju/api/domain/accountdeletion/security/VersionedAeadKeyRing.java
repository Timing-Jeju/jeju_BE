package com.timingjeju.api.domain.accountdeletion.security;

public interface VersionedAeadKeyRing {
  EncryptedSecret encrypt(SecretPurpose purpose, byte[] plaintext);

  byte[] decrypt(SecretPurpose purpose, EncryptedSecret secret);
}
