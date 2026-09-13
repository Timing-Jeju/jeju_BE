package com.timingjeju.api.domain.accountdeletion.worker;

import java.util.Arrays;

public final class EncryptedAuthSubject {

  private final byte[] ciphertext;
  private final String keyVersion;

  public EncryptedAuthSubject(byte[] ciphertext, String keyVersion) {
    if (ciphertext == null || ciphertext.length == 0) {
      throw new IllegalArgumentException("ciphertext는 비어 있을 수 없습니다.");
    }
    if (keyVersion == null || keyVersion.isBlank()) {
      throw new IllegalArgumentException("keyVersion은 필수입니다.");
    }
    this.ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
    this.keyVersion = keyVersion;
  }

  public byte[] ciphertext() {
    return Arrays.copyOf(ciphertext, ciphertext.length);
  }

  public String keyVersion() {
    return keyVersion;
  }

  @Override
  public String toString() {
    return "EncryptedAuthSubject[keyVersion=" + keyVersion + ", ciphertext=<redacted>]";
  }
}
