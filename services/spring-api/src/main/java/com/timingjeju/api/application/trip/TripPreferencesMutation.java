package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TripPreferencesMutation(
    UUID tripId,
    ReplaceTripPreferencesCommand preferences,
    long revision,
    Instant updatedAt,
    String scheduleEffect,
    boolean regenerationRequired,
    UUID activeScheduleVersionId,
    String tripStatus,
    String etag) {
  public TripPreferencesMutation {
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(preferences);
    Objects.requireNonNull(updatedAt);
    Objects.requireNonNull(scheduleEffect);
    Objects.requireNonNull(tripStatus);
    Objects.requireNonNull(etag);
  }
}
