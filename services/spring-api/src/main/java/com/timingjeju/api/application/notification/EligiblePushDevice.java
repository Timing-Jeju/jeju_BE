package com.timingjeju.api.application.notification;

import java.util.UUID;

public record EligiblePushDevice(
    UUID deviceId,
    PushPlatform platform,
    String registrationToken,
    int safetyBufferMinutes,
    UUID locationConsentDocumentId,
    String locationConsentVersion) {

  @Override
  public String toString() {
    return "EligiblePushDevice[deviceId=%s, platform=%s, registrationToken=<redacted>, safetyBufferMinutes=%d, locationConsentDocumentId=%s, locationConsentVersion=%s]"
        .formatted(
            deviceId,
            platform,
            safetyBufferMinutes,
            locationConsentDocumentId,
            locationConsentVersion);
  }
}
