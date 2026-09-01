package com.timingjeju.api.domain.schedule.dto;

import com.timingjeju.api.application.schedule.ScheduleDaySnapshot;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(name = "ScheduleDay", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ScheduleDayResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID dayId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int dayNo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date") LocalDate date,
    @ArraySchema(
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
            schema = @Schema(implementation = ScheduleItemResponse.class))
        List<ScheduleItemResponse> items,
    @ArraySchema(
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
            schema = @Schema(implementation = ScheduleLegResponse.class))
        List<ScheduleLegResponse> legs) {
  static ScheduleDayResponse from(ScheduleDaySnapshot day) {
    return new ScheduleDayResponse(
        day.dayId(),
        day.dayNo(),
        day.date(),
        day.items().stream().map(ScheduleItemResponse::from).toList(),
        day.legs().stream().map(ScheduleLegResponse::from).toList());
  }
}
