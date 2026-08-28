package com.timingjeju.api.application.notification;

import java.util.UUID;

public record StoredEligiblePushDevice(
    UUID deviceId,
    PushPlatform platform,
    String tokenCiphertext,
    int safetyBufferMinutes,
    UUID locationConsentDocumentId,
    String locationConsentVersion) {

  @Override
  public String toString() {
    return "StoredEligiblePushDevice[deviceId=%s, platform=%s, tokenCiphertext=<redacted>, safetyBufferMinutes=%d, locationConsentDocumentId=%s, locationConsentVersion=%s]"
        .formatted(
            deviceId,
            platform,
            safetyBufferMinutes,
            locationConsentDocumentId,
            locationConsentVersion);
  }
}
