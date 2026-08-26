package com.timingjeju.api.domain.notification.dto.response;

import com.timingjeju.api.application.notification.NotificationPreference;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record NotificationPreferenceResponse(
    boolean nextDestinationDepartureEnabled,
    @Schema(minimum = "0", maximum = "120") int safetyBufferMinutes,
    @Schema(nullable = true) Instant updatedAt) {

  public static NotificationPreferenceResponse from(NotificationPreference preference) {
    return new NotificationPreferenceResponse(
        preference.nextDestinationDepartureEnabled(),
        preference.safetyBufferMinutes(),
        preference.updatedAt());
  }
}
