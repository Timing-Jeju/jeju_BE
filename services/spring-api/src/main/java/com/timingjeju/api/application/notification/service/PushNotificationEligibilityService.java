package com.timingjeju.api.application.notification.service;

import com.timingjeju.api.application.notification.EligiblePushDevice;
import com.timingjeju.api.application.notification.PushEligibilityStore;
import com.timingjeju.api.application.notification.PushNotificationException;
import com.timingjeju.api.application.notification.RegistrationTokenProtectionFailure;
import com.timingjeju.api.application.notification.RegistrationTokenProtector;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PushNotificationEligibilityService {

  private final PushEligibilityStore eligibility;
  private final RegistrationTokenProtector tokens;
  private final Clock clock;

  public PushNotificationEligibilityService(
      PushEligibilityStore eligibility, RegistrationTokenProtector tokens, Clock clock) {
    this.eligibility = Objects.requireNonNull(eligibility);
    this.tokens = Objects.requireNonNull(tokens);
    this.clock = Objects.requireNonNull(clock);
  }

  public List<EligiblePushDevice> findEligible(UUID userId) {
    try {
      return eligibility.findEligible(userId, clock.instant()).stream()
          .map(
              stored ->
                  new EligiblePushDevice(
                      stored.deviceId(),
                      stored.platform(),
                      tokens.reveal(stored.tokenCiphertext()),
                      stored.safetyBufferMinutes(),
                      stored.locationConsentDocumentId(),
                      stored.locationConsentVersion()))
          .toList();
    } catch (RegistrationTokenProtectionFailure failure) {
      throw PushNotificationException.dataUnavailable();
    }
  }
}
