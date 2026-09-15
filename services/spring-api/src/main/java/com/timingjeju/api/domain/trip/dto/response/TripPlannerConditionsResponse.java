package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripPlannerConditions;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(name = "PlannerConditions", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TripPlannerConditionsResponse(
    @ArraySchema(
            minItems = 0,
            maxItems = 30,
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
            schema = @Schema(implementation = DayAnchor.class))
        List<DayAnchor> dayAnchors,
    @ArraySchema(
            minItems = 0,
            maxItems = 7,
            uniqueItems = true,
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
            schema =
                @Schema(
                    type = "string",
                    allowableValues = {
                      "restaurant",
                      "cafe",
                      "leisure",
                      "cultural_facility",
                      "relaxed",
                      "trendy",
                      "local"
                    }))
        List<String> styleCodes) {
  public static TripPlannerConditionsResponse from(TripPlannerConditions value) {
    return new TripPlannerConditionsResponse(
        value.dayAnchors().stream()
            .map(anchor -> new DayAnchor(anchor.dayId(), anchor.lodgingPlaceId()))
            .toList(),
        value.styleCodes());
  }

  @Schema(name = "PlannerDayAnchor", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record DayAnchor(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID dayId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID lodgingPlaceId) {}
}
