package com.timingjeju.api.domain.generation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.timingjeju.api.application.generation.GenerationApplyStore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Schema(
    name = "ApplyCandidateResponse",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
    requiredProperties = {
      "contractVersion",
      "tripId",
      "runId",
      "candidateId",
      "previousScheduleVersionId",
      "activeScheduleVersionId",
      "appliedAt"
    })
public record GenerationAppliedResponse(
    @Schema(allowableValues = {"1.0.0"}) String contractVersion,
    UUID tripId,
    UUID runId,
    UUID candidateId,
    @JsonInclude(JsonInclude.Include.ALWAYS) @Schema(nullable = true)
        UUID previousScheduleVersionId,
    UUID activeScheduleVersionId,
    OffsetDateTime appliedAt) {
  public static GenerationAppliedResponse from(GenerationApplyStore.Applied result) {
    return new GenerationAppliedResponse(
        "1.0.0",
        result.tripId(),
        result.runId(),
        result.candidateId(),
        result.previousScheduleVersionId(),
        result.activeScheduleVersionId(),
        result.appliedAt().atOffset(ZoneOffset.ofHours(9)));
  }
}
