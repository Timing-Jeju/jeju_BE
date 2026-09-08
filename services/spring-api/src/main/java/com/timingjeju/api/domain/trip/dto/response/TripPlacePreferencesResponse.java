package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripPlacePreferencesMutation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(
    name = "PlacePreferencesResponse",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TripPlacePreferencesResponse(
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
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TripPlacePreferenceResponse> items) {
  public TripPlacePreferencesResponse {
    items = List.copyOf(items);
  }

  public static TripPlacePreferencesResponse from(TripPlacePreferencesMutation mutation) {
    return new TripPlacePreferencesResponse(
        mutation.tripId(),
        mutation.scheduleEffect(),
        mutation.regenerationRequired(),
        mutation.activeScheduleVersionId(),
        mutation.tripStatus(),
        mutation.updatedAt(),
        mutation.preferences().stream().map(TripPlacePreferenceResponse::from).toList());
  }
}
