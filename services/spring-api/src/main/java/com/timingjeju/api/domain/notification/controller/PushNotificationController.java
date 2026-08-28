package com.timingjeju.api.domain.notification.controller;

import com.timingjeju.api.application.notification.PushNotificationException;
import com.timingjeju.api.application.notification.service.NotificationPreferenceService;
import com.timingjeju.api.application.notification.service.PushDeviceService;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.domain.notification.controller.docs.PushNotificationApiDocs;
import com.timingjeju.api.domain.notification.dto.request.NotificationPreferencePatchRequest;
import com.timingjeju.api.domain.notification.dto.request.PushDeviceRegistrationRequest;
import com.timingjeju.api.domain.notification.dto.response.NotificationPreferenceResponse;
import com.timingjeju.api.domain.notification.dto.response.PushDeviceResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class PushNotificationController implements PushNotificationApiDocs {

  private final PushDeviceService devices;
  private final NotificationPreferenceService preferences;
  private final CurrentUserAccessor currentUsers;

  public PushNotificationController(
      PushDeviceService devices,
      NotificationPreferenceService preferences,
      CurrentUserAccessor currentUsers) {
    this.devices = devices;
    this.preferences = preferences;
    this.currentUsers = currentUsers;
  }

  @Override
  @PutMapping("/push-devices/{deviceId}")
  public PushDeviceResponse register(
      @PathVariable String deviceId, @RequestBody PushDeviceRegistrationRequest request) {
    return PushDeviceResponse.from(
        devices.register(
            currentUsers.getRequired(), canonicalDeviceId(deviceId), request.toRegistration()));
  }

  @Override
  @DeleteMapping("/push-devices/{deviceId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void invalidate(@PathVariable String deviceId) {
    devices.invalidate(currentUsers.getRequired(), canonicalDeviceId(deviceId));
  }

  @Override
  @GetMapping("/notification-preferences")
  public NotificationPreferenceResponse readPreferences() {
    return NotificationPreferenceResponse.from(preferences.read(currentUsers.getRequired()));
  }

  @Override
  @PatchMapping("/notification-preferences")
  public NotificationPreferenceResponse updatePreferences(
      @RequestBody NotificationPreferencePatchRequest request) {
    return NotificationPreferenceResponse.from(
        preferences.update(currentUsers.getRequired(), request.toPatch()));
  }

  private static UUID canonicalDeviceId(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw PushNotificationException.invalidRequest();
      }
      return parsed;
    } catch (IllegalArgumentException failure) {
      throw PushNotificationException.invalidRequest();
    }
  }
}
