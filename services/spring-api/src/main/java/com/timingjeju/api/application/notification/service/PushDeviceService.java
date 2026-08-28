package com.timingjeju.api.application.notification.service;

import com.timingjeju.api.application.notification.ProtectedPushDeviceRegistration;
import com.timingjeju.api.application.notification.ProtectedRegistrationToken;
import com.timingjeju.api.application.notification.PushDevice;
import com.timingjeju.api.application.notification.PushDeviceRegistration;
import com.timingjeju.api.application.notification.PushDeviceStore;
import com.timingjeju.api.application.notification.PushLocalePolicy;
import com.timingjeju.api.application.notification.PushNotificationException;
import com.timingjeju.api.application.notification.RegistrationTokenPolicy;
import com.timingjeju.api.application.notification.RegistrationTokenProtectionFailure;
import com.timingjeju.api.application.notification.RegistrationTokenProtector;
import com.timingjeju.api.application.security.CurrentUser;
import java.time.Clock;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.Objects;
import java.util.UUID;

public final class PushDeviceService {

  private final PushDeviceStore devices;
  private final RegistrationTokenProtector tokens;
  private final Clock clock;

  public PushDeviceService(
      PushDeviceStore devices, RegistrationTokenProtector tokens, Clock clock) {
    this.devices = Objects.requireNonNull(devices);
    this.tokens = Objects.requireNonNull(tokens);
    this.clock = Objects.requireNonNull(clock);
  }

  public PushDevice register(CurrentUser user, UUID deviceId, PushDeviceRegistration registration) {
    validate(registration);
    ProtectedRegistrationToken token;
    try {
      token = tokens.protect(registration.registrationToken());
    } catch (RegistrationTokenProtectionFailure failure) {
      throw PushNotificationException.dataUnavailable();
    }
    return devices.register(
        user.userId(),
        deviceId,
        new ProtectedPushDeviceRegistration(
            registration.platform(),
            token.ciphertext(),
            token.fingerprint(),
            registration.permissionStatus(),
            registration.appVersion(),
            registration.locale(),
            registration.timeZone()),
        clock.instant());
  }

  public void invalidate(CurrentUser user, UUID deviceId) {
    devices.invalidate(user.userId(), deviceId, clock.instant());
  }

  private static void validate(PushDeviceRegistration value) {
    if (value == null
        || value.platform() == null
        || value.permissionStatus() == null
        || value.registrationToken() == null
        || value.appVersion() == null
        || value.appVersion().isBlank()
        || value.appVersion().length() > 50
        || value.locale() == null
        || value.timeZone() == null
        || value.timeZone().length() > 64) {
      throw PushNotificationException.invalidRequest();
    }
    RegistrationTokenPolicy.requireValid(value.registrationToken());
    try {
      PushLocalePolicy.requireValid(value.locale());
      ZoneId.of(value.timeZone());
    } catch (ZoneRulesException | IllegalArgumentException failure) {
      throw PushNotificationException.invalidRequest();
    }
  }
}
