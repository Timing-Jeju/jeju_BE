package com.timingjeju.api.domain.accountdeletion.security;

public final class SecretDecryptionException extends RuntimeException {
  public SecretDecryptionException() {
    super("account deletion secret could not be decrypted", null, false, false);
  }
}
