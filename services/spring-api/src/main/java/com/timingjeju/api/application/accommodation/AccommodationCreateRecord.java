package com.timingjeju.api.application.accommodation;

import com.timingjeju.api.application.trip.TripExpectedRevision;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccommodationCreateRecord(
    UUID ownerId,
    UUID tripId,
    String idempotencyKey,
    String requestHash,
    TripExpectedRevision expected,
    UUID accommodationId,
    CreateAccommodationCommand command,
    Instant now) {
  public AccommodationCreateRecord {
    Objects.requireNonNull(ownerId);
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(idempotencyKey);
    Objects.requireNonNull(requestHash);
    Objects.requireNonNull(expected);
    Objects.requireNonNull(accommodationId);
    Objects.requireNonNull(command);
    Objects.requireNonNull(now);
  }
}
