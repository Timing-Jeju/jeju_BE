package com.timingjeju.api.application.schedule;

import com.timingjeju.api.application.trip.TripExpectedRevision;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ScheduleEditRecord<T>(
    UUID ownerId,
    UUID tripId,
    UUID itemId,
    TripExpectedRevision expectedTrip,
    T command,
    Instant transactionTime) {
  public ScheduleEditRecord {
    Objects.requireNonNull(ownerId);
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(expectedTrip);
    Objects.requireNonNull(command);
    Objects.requireNonNull(transactionTime);
  }
}
