package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripPreferences;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(name = "Preferences", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TripPreferencesDetailsResponse(
    @ArraySchema(
            minItems = 0,
            maxItems = 8,
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
            schema =
                @Schema(
                    type = "string",
                    allowableValues = {
                      "tourist_attraction",
                      "cultural_facility",
                      "festival",
                      "travel_course",
                      "leisure",
                      "restaurant",
                      "cafe",
                      "shopping"
                    }))
        List<String> preferredCategories,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 50)
        String arrivalRegionCode,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 50)
        String departureRegionCode,
    @ArraySchema(
            minItems = 0,
            maxItems = 20,
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
            schema = @Schema(type = "string", minLength = 1, maxLength = 50))
        List<String> preferredRegionCodes,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"string", "null"},
            format = "uuid")
        UUID startPlaceId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"string", "null"},
            format = "uuid")
        UUID endPlaceId,
    @ArraySchema(
            minItems = 1,
            maxItems = 3,
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
            schema = @Schema(implementation = TripTransportModeResponse.class))
        List<TripTransportModeResponse> transportModes) {
  public static TripPreferencesDetailsResponse from(TripPreferences value) {
    return new TripPreferencesDetailsResponse(
        value.preferredCategories(),
        value.arrivalRegionCode(),
        value.departureRegionCode(),
        value.preferredRegionCodes(),
        value.startPlaceId(),
        value.endPlaceId(),
        value.transportModes().stream().map(TripTransportModeResponse::from).toList());
  }
}
