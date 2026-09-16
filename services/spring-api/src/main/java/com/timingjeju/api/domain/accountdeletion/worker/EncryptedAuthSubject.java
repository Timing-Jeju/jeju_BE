package com.timingjeju.api.domain.accountdeletion.worker;

public final class EncryptedAuthSubject {

  private final String ciphertext;
  private final String keyVersion;

  public EncryptedAuthSubject(String ciphertext, String keyVersion) {
    if (ciphertext == null || ciphertext.isBlank()) {
      throw new IllegalArgumentException("ciphertext는 비어 있을 수 없습니다.");
    }
    if (keyVersion == null || keyVersion.isBlank()) {
      throw new IllegalArgumentException("keyVersion은 필수입니다.");
    }
    this.ciphertext = ciphertext;
    this.keyVersion = keyVersion;
  }

  public String ciphertext() {
    return ciphertext;
  }

  public String keyVersion() {
    return keyVersion;
  }

  @Override
  public String toString() {
    return "EncryptedAuthSubject[keyVersion=" + keyVersion + ", ciphertext=<redacted>]";
  }
}
