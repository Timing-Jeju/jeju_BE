package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripPreferencesMutation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "PreferencesResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TripPreferencesResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID tripId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"none", "maintained", "invalidated"})
        String scheduleEffect,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean regenerationRequired,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"string", "null"},
            format = "uuid")
        UUID activeScheduleVersionId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"draft", "generating", "planned", "live"})
        String tripStatus,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant updatedAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        TripPreferencesDetailsResponse preferences) {
  public static TripPreferencesResponse from(TripPreferencesMutation result) {
    return new TripPreferencesResponse(
        result.tripId(),
        result.scheduleEffect(),
        result.regenerationRequired(),
        result.activeScheduleVersionId(),
        result.tripStatus(),
        result.updatedAt(),
        TripPreferencesDetailsResponse.from(result.preferences()));
  }
}
