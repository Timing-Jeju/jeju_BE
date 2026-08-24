package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.util.UUID;

public record TripScoreProvenance(
    UUID runId,
    UUID scheduleVersionId,
    Instant calculatedAt,
    Instant observedAt,
    Instant expiresAt,
    boolean stale) {}
