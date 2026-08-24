package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripScoreProvenance;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "ScoreProvenance", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TripScoreProvenanceResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "feasibility_run")
        String source,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID runId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID scheduleVersionId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant calculatedAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant observedAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant expiresAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean stale) {
  static TripScoreProvenanceResponse from(TripScoreProvenance source) {
    if (source == null) {
      return null;
    }
    return new TripScoreProvenanceResponse(
        "feasibility_run",
        source.runId(),
        source.scheduleVersionId(),
        source.calculatedAt(),
        source.observedAt(),
        source.expiresAt(),
        source.stale());
  }
}
