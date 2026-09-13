package com.timingjeju.api.domain.accountdeletion.security;

public record EncryptedSecret(String ciphertext, String keyVersion) {
  public EncryptedSecret {
    if (ciphertext == null || ciphertext.isBlank() || keyVersion == null || keyVersion.isBlank()) {
      throw new IllegalArgumentException("암호문과 key version은 필수입니다.");
    }
  }

  @Override
  public String toString() {
    return "EncryptedSecret[ciphertext=<redacted>, keyVersion=" + keyVersion + "]";
  }
}
