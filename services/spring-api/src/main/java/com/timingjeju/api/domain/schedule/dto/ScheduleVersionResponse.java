package com.timingjeju.api.domain.schedule.dto;

import com.timingjeju.api.application.schedule.ScheduleVersionSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "ScheduleVersion", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ScheduleVersionResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID scheduleVersionId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int versionNo,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"draft", "candidate", "active", "superseded", "rejected"})
        String status,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {
              "initial",
              "user_edit",
              "ai_generation",
              "recovery",
              "live_recalculation"
            })
        String sourceType,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"string", "null"},
            format = "uuid")
        UUID baseScheduleVersionId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"integer", "null"},
            minimum = "0",
            maximum = "100")
        Integer score,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean feasibilityStale) {
  static ScheduleVersionResponse from(ScheduleVersionSnapshot version) {
    return new ScheduleVersionResponse(
        version.scheduleVersionId(),
        version.versionNo(),
        version.status(),
        version.sourceType(),
        version.baseScheduleVersionId(),
        version.score(),
        version.feasibilityStale());
  }
}
