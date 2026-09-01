package com.timingjeju.api.application.accommodation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccommodationPatchRecord(
    UUID ownerId,
    UUID tripId,
    UUID accommodationId,
    long expectedRevision,
    PatchAccommodationCommand command,
    Instant now) {
  public AccommodationPatchRecord {
    Objects.requireNonNull(ownerId);
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(accommodationId);
    Objects.requireNonNull(command);
    Objects.requireNonNull(now);
  }
}
