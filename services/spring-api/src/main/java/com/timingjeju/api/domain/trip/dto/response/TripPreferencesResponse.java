package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.ReplaceTripPreferencesCommand;
import com.timingjeju.api.application.trip.TripPreferencesMutation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
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
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String tripStatus,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant updatedAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Preferences preferences) {

  public static TripPreferencesResponse from(TripPreferencesMutation source) {
    return new TripPreferencesResponse(
        source.tripId(),
        source.scheduleEffect(),
        source.regenerationRequired(),
        source.activeScheduleVersionId(),
        source.tripStatus(),
        source.updatedAt(),
        Preferences.from(source.preferences()));
  }

  @Schema(name = "TripPreferences", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Preferences(
      @ArraySchema(
              minItems = 0,
              maxItems = 8,
              uniqueItems = true,
              arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
          List<String> preferredCategories,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 50)
          String arrivalRegionCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 50)
          String departureRegionCode,
      @ArraySchema(
              minItems = 0,
              maxItems = 20,
              uniqueItems = true,
              arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
          List<String> preferredRegionCodes,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, format = "uuid")
          UUID startPlaceId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, format = "uuid")
          UUID endPlaceId,
      @ArraySchema(
              minItems = 1,
              maxItems = 3,
              arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
              schema = @Schema(implementation = TripTransportModeResponse.class))
          List<TripTransportModeResponse> transportModes) {
    static Preferences from(ReplaceTripPreferencesCommand source) {
      return new Preferences(
          source.preferredCategories(),
          source.arrivalRegionCode(),
          source.departureRegionCode(),
          source.preferredRegionCodes(),
          source.startPlaceId(),
          source.endPlaceId(),
          source.transportModes().stream().map(TripTransportModeResponse::from).toList());
    }
  }
}
