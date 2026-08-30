package com.timingjeju.api.application.notification;

public record PushDeviceRegistration(
    PushPlatform platform,
    String registrationToken,
    PushPermissionStatus permissionStatus,
    String appVersion,
    String locale,
    String timeZone) {

  @Override
  public String toString() {
    return "PushDeviceRegistration[platform=%s, registrationToken=<redacted>, permissionStatus=%s, appVersion=%s, locale=%s, timeZone=%s]"
        .formatted(platform, permissionStatus, appVersion, locale, timeZone);
  }
}
