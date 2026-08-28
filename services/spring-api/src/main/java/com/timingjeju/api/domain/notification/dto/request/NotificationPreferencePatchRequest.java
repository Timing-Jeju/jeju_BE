package com.timingjeju.api.domain.notification.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.notification.NotificationPreferencePatch;
import com.timingjeju.api.application.notification.PushNotificationException;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE, minProperties = 1)
public final class NotificationPreferencePatchRequest {

  private Boolean enabled;
  private boolean enabledPresent;
  private Integer safetyBufferMinutes;
  private boolean safetyBufferPresent;

  public Boolean getNextDestinationDepartureEnabled() {
    return enabled;
  }

  @JsonSetter("nextDestinationDepartureEnabled")
  public void setNextDestinationDepartureEnabled(Object value) {
    if (!(value instanceof Boolean booleanValue)) {
      throw PushNotificationException.invalidRequest();
    }
    enabled = booleanValue;
    enabledPresent = true;
  }

  @Schema(minimum = "0", maximum = "120")
  public Integer getSafetyBufferMinutes() {
    return safetyBufferMinutes;
  }

  @JsonSetter("safetyBufferMinutes")
  public void setSafetyBufferMinutes(Object value) {
    if (!(value instanceof Integer integerValue)) {
      throw PushNotificationException.invalidRequest();
    }
    if (integerValue < 0 || integerValue > 120) {
      throw PushNotificationException.invalidRequest();
    }
    safetyBufferMinutes = integerValue;
    safetyBufferPresent = true;
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw PushNotificationException.invalidRequest();
  }

  public NotificationPreferencePatch toPatch() {
    if (!enabledPresent && !safetyBufferPresent) {
      throw PushNotificationException.invalidRequest();
    }
    return new NotificationPreferencePatch(
        enabledPresent, enabled, safetyBufferPresent, safetyBufferMinutes);
  }
}
