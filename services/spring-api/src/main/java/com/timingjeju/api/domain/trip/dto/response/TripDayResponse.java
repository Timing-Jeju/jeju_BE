package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripDay;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "TripDay", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TripDayResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID dayId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "30") int dayNo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date") LocalDate date) {
  static TripDayResponse from(TripDay day) {
    return new TripDayResponse(day.dayId(), day.dayNo(), day.date());
  }
}
