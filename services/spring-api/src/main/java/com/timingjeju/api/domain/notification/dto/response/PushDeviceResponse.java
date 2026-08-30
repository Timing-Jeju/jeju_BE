package com.timingjeju.api.domain.notification.dto.response;

import com.timingjeju.api.application.notification.PushDevice;
import com.timingjeju.api.application.notification.PushPermissionStatus;
import com.timingjeju.api.application.notification.PushPlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PushDeviceResponse(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        UUID deviceId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PushPlatform platform,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PushPermissionStatus permissionStatus,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean active,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt) {

  public static PushDeviceResponse from(PushDevice device) {
    return new PushDeviceResponse(
        device.deviceId(),
        device.platform(),
        device.permissionStatus(),
        device.active(),
        device.updatedAt());
  }
}
