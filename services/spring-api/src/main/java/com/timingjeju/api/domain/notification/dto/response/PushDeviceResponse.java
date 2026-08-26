package com.timingjeju.api.domain.notification.dto.response;

import com.timingjeju.api.application.notification.PushDevice;
import com.timingjeju.api.application.notification.PushPermissionStatus;
import com.timingjeju.api.application.notification.PushPlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PushDeviceResponse(
    UUID deviceId,
    PushPlatform platform,
    PushPermissionStatus permissionStatus,
    boolean active,
    Instant updatedAt) {

  public static PushDeviceResponse from(PushDevice device) {
    return new PushDeviceResponse(
        device.deviceId(),
        device.platform(),
        device.permissionStatus(),
        device.active(),
        device.updatedAt());
  }
}
