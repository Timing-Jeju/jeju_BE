package com.timingjeju.api.domain.notification.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.notification.PushDeviceRegistration;
import com.timingjeju.api.application.notification.PushLocalePolicy;
import com.timingjeju.api.application.notification.PushNotificationException;
import com.timingjeju.api.application.notification.PushPermissionStatus;
import com.timingjeju.api.application.notification.PushPlatform;
import com.timingjeju.api.application.notification.RegistrationTokenPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZoneId;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class PushDeviceRegistrationRequest {

  private String platform;
  private String registrationToken;
  private String permissionStatus;
  private String appVersion;
  private String locale;
  private String timeZone;

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      allowableValues = {"IOS", "ANDROID"})
  public String getPlatform() {
    return platform;
  }

  @JsonSetter("platform")
  public void setPlatform(Object value) {
    platform = string(value);
  }

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      minLength = 1,
      maxLength = RegistrationTokenPolicy.MAX_PLAINTEXT_BYTES,
      pattern = RegistrationTokenPolicy.ASCII_PATTERN,
      description = "FCM-compatible printable ASCII token; UTF-8 기준 최대 4096 bytes")
  public String getRegistrationToken() {
    return registrationToken;
  }

  @JsonSetter("registrationToken")
  public void setRegistrationToken(Object value) {
    registrationToken = string(value);
  }

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      allowableValues = {"GRANTED", "DENIED", "NOT_DETERMINED"})
  public String getPermissionStatus() {
    return permissionStatus;
  }

  @JsonSetter("permissionStatus")
  public void setPermissionStatus(Object value) {
    permissionStatus = string(value);
  }

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 50)
  public String getAppVersion() {
    return appVersion;
  }

  @JsonSetter("appVersion")
  public void setAppVersion(Object value) {
    appVersion = string(value);
  }

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "en-US-u-ca-gregory",
      maxLength = PushLocalePolicy.MAX_LENGTH,
      pattern = PushLocalePolicy.BCP47_PATTERN,
      description = "canonical BCP 47 locale; 2..35 characters")
  public String getLocale() {
    return locale;
  }

  @JsonSetter("locale")
  public void setLocale(Object value) {
    locale = string(value);
  }

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Asia/Seoul", maxLength = 64)
  public String getTimeZone() {
    return timeZone;
  }

  @JsonSetter("timeZone")
  public void setTimeZone(Object value) {
    timeZone = string(value);
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw PushNotificationException.invalidRequest();
  }

  public PushDeviceRegistration toRegistration() {
    try {
      PushDeviceRegistration registration =
          new PushDeviceRegistration(
              PushPlatform.valueOf(required(platform)),
              required(registrationToken),
              PushPermissionStatus.valueOf(required(permissionStatus)),
              required(appVersion),
              required(locale),
              required(timeZone));
      PushLocalePolicy.requireValid(registration.locale());
      RegistrationTokenPolicy.requireValid(registration.registrationToken());
      if (registration.appVersion().isBlank()
          || registration.appVersion().length() > 50
          || registration.locale().length() > PushLocalePolicy.MAX_LENGTH
          || registration.timeZone().length() > 64) {
        throw PushNotificationException.invalidRequest();
      }
      ZoneId.of(registration.timeZone());
      return registration;
    } catch (IllegalArgumentException | java.time.DateTimeException failure) {
      throw PushNotificationException.invalidRequest();
    }
  }

  private static String string(Object value) {
    if (!(value instanceof String text)) {
      throw PushNotificationException.invalidRequest();
    }
    return text;
  }

  private static String required(String value) {
    if (value == null) {
      throw PushNotificationException.invalidRequest();
    }
    return value;
  }
}
