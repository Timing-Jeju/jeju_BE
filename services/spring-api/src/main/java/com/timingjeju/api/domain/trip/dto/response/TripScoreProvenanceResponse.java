package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripScoreProvenance;
import java.time.Instant;
import java.util.UUID;

public record TripScoreProvenanceResponse(
    String source,
    UUID runId,
    UUID scheduleVersionId,
    Instant calculatedAt,
    Instant observedAt,
    Instant expiresAt,
    boolean stale) {
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
