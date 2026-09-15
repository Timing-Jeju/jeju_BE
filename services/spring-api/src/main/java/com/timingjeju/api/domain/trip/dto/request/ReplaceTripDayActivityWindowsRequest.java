package com.timingjeju.api.domain.trip.dto.request;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ReplaceTripDayActivityWindowsRequest(
    @ArraySchema(
            minItems = 1,
            maxItems = 30,
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
        List<DayWindow> days) {
  @Schema(
      name = "TripDayActivityWindowInput",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record DayWindow(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID dayId,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              pattern = "^(?:[01][0-9]|2[0-3]):[0-5][0-9]$")
          String startTime,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              pattern = "^(?:[01][0-9]|2[0-3]):[0-5][0-9]$")
          String endTime) {}
}
