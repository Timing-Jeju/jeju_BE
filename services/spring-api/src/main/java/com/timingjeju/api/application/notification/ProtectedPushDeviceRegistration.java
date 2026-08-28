package com.timingjeju.api.application.notification;

public record ProtectedPushDeviceRegistration(
    PushPlatform platform,
    String tokenCiphertext,
    byte[] tokenFingerprint,
    PushPermissionStatus permissionStatus,
    String appVersion,
    String locale,
    String timeZone) {

  public ProtectedPushDeviceRegistration {
    tokenFingerprint = tokenFingerprint.clone();
  }

  @Override
  public byte[] tokenFingerprint() {
    return tokenFingerprint.clone();
  }

  @Override
  public String toString() {
    return "ProtectedPushDeviceRegistration[platform=%s, tokenCiphertext=<redacted>, tokenFingerprint=<redacted>, permissionStatus=%s, appVersion=%s, locale=%s, timeZone=%s]"
        .formatted(platform, permissionStatus, appVersion, locale, timeZone);
  }
}
