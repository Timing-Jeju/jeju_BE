package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripDay;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Schema(name = "TripDay", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TripDayResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID dayId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "30") int dayNo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date") LocalDate date,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"string", "null"},
            pattern = "^(?:[01][0-9]|2[0-3]):[0-5][0-9]$")
        String activityStartTime,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"string", "null"},
            pattern = "^(?:[01][0-9]|2[0-3]):[0-5][0-9]$")
        String activityEndTime) {
  static TripDayResponse from(TripDay day) {
    return new TripDayResponse(
        day.dayId(),
        day.dayNo(),
        day.date(),
        format(day.activityStartTime()),
        format(day.activityEndTime()));
  }

  private static String format(LocalTime value) {
    if (value != null && (value.getSecond() != 0 || value.getNano() != 0)) {
      throw com.timingjeju.api.application.trip.TripException.dataUnavailable();
    }
    return value == null ? null : value.format(DateTimeFormatter.ofPattern("HH:mm"));
  }
}
