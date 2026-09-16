package com.timingjeju.api.domain.accountdeletion.security;

public enum SecretPurpose {
  STATUS_TOKEN("timing-jeju:account-deletion:status-token:v1"),
  AUTH_SUBJECT("timing-jeju:account-deletion:auth-subject:v1");

  private final String aad;

  SecretPurpose(String aad) {
    this.aad = aad;
  }

  public byte[] aad() {
    return aad.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  }
}
