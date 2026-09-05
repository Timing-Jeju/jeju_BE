package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripPlacePreference;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "PlacePreferenceItem", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TripPlacePreferenceResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID placeId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"must_visit", "avoid"})
        String type,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"integer", "null"},
            minimum = "1",
            maximum = "30")
        Integer targetDayNo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "100")
        int priority) {
  public static TripPlacePreferenceResponse from(TripPlacePreference preference) {
    return new TripPlacePreferenceResponse(
        preference.placeId(), preference.type(), preference.targetDayNo(), preference.priority());
  }
}
