package com.timingjeju.api.domain.schedule.dto;

import com.timingjeju.api.application.schedule.ScheduleSnapshot;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(name = "ScheduleResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ScheduleResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID tripId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ScheduleVersionResponse scheduleVersion,
    @ArraySchema(
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
            schema = @Schema(implementation = ScheduleDayResponse.class))
        List<ScheduleDayResponse> days) {
  public static ScheduleResponse from(ScheduleSnapshot schedule) {
    return new ScheduleResponse(
        schedule.tripId(),
        ScheduleVersionResponse.from(schedule.scheduleVersion()),
        schedule.days().stream().map(ScheduleDayResponse::from).toList());
  }
}
