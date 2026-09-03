package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record TripUpdateRecord(
    UUID ownerId,
    UUID tripId,
    TripExpectedRevision expected,
    PatchTripCommand command,
    List<UUID> dayIds,
    Instant updatedAt) {
  public TripUpdateRecord {
    Objects.requireNonNull(ownerId);
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(expected);
    Objects.requireNonNull(command);
    dayIds = List.copyOf(dayIds);
    Objects.requireNonNull(updatedAt);
  }
}
