package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.util.UUID;

public record TripScore(Integer totalScore, TripScoreProvenance provenance) {
  private static final TripScore EMPTY = new TripScore(null, null);

  public static TripScore resolve(
      UUID activeScheduleVersionId,
      Integer totalScore,
      UUID runId,
      UUID runScheduleVersionId,
      Instant calculatedAt,
      Instant observedAt,
      Instant expiresAt,
      Instant responseTime) {
    if (activeScheduleVersionId == null
        || totalScore == null
        || totalScore < 0
        || totalScore > 100
        || runId == null
        || !activeScheduleVersionId.equals(runScheduleVersionId)
        || calculatedAt == null
        || observedAt == null
        || expiresAt == null
        || responseTime == null
        || observedAt.isAfter(calculatedAt)
        || calculatedAt.isAfter(expiresAt)) {
      return EMPTY;
    }
    return new TripScore(
        totalScore,
        new TripScoreProvenance(
            runId,
            runScheduleVersionId,
            calculatedAt,
            observedAt,
            expiresAt,
            !expiresAt.isAfter(responseTime)));
  }
}
