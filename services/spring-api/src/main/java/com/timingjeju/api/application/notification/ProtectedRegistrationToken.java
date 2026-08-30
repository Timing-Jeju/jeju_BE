package com.timingjeju.api.application.notification;

public record ProtectedRegistrationToken(String ciphertext, byte[] fingerprint) {

  public ProtectedRegistrationToken {
    fingerprint = fingerprint.clone();
  }

  @Override
  public byte[] fingerprint() {
    return fingerprint.clone();
  }

  @Override
  public String toString() {
    return "ProtectedRegistrationToken[ciphertext=<redacted>, fingerprint=<redacted>]";
  }
}
