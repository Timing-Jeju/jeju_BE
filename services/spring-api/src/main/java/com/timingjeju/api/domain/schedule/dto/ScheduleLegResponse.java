package com.timingjeju.api.domain.schedule.dto;

import com.timingjeju.api.application.schedule.ScheduleLegSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Schema(name = "ScheduleLeg", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ScheduleLegResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID legId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int sequenceNo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID fromItemId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID toItemId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"walk", "public_transit", "rental_car", "taxi"})
        String transportMode,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time")
        OffsetDateTime plannedDepartureAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time")
        OffsetDateTime plannedArrivalAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") int walkMinutes,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") int waitMinutes,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") int rideMinutes,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") int transferMinutes,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int durationMinutes,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") int bufferMinutes,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"integer", "null"},
            minimum = "0")
        Integer distanceMeters,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"integer", "null"},
            minimum = "0")
        Integer estimatedFareKrw,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"integer", "null"},
            minimum = "0",
            maximum = "100")
        Integer riskScore) {
  private static final ZoneId JEJU = ZoneId.of("Asia/Seoul");

  static ScheduleLegResponse from(ScheduleLegSnapshot leg) {
    return new ScheduleLegResponse(
        leg.legId(),
        leg.sequenceNo(),
        leg.fromItemId(),
        leg.toItemId(),
        leg.transportMode(),
        leg.plannedDepartureAt().atZone(JEJU).toOffsetDateTime(),
        leg.plannedArrivalAt().atZone(JEJU).toOffsetDateTime(),
        leg.walkMinutes(),
        leg.waitMinutes(),
        leg.rideMinutes(),
        leg.transferMinutes(),
        leg.durationMinutes(),
        leg.bufferMinutes(),
        leg.distanceMeters(),
        leg.estimatedFareKrw(),
        leg.riskScore());
  }
}
