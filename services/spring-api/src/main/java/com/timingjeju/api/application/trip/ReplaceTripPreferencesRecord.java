package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ReplaceTripPreferencesRecord(
    UUID ownerId,
    UUID tripId,
    long expectedRevision,
    ReplaceTripPreferencesCommand command,
    Instant updatedAt) {
  public ReplaceTripPreferencesRecord {
    Objects.requireNonNull(ownerId);
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(command);
    Objects.requireNonNull(updatedAt);
  }
}
