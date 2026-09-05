package com.timingjeju.api.application.accommodation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccommodationDeleteRecord(
    UUID ownerId, UUID tripId, UUID accommodationId, long expectedRevision, Instant now) {
  public AccommodationDeleteRecord {
    Objects.requireNonNull(ownerId);
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(accommodationId);
    Objects.requireNonNull(now);
  }
}
