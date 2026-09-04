package com.timingjeju.api.application.schedule;

import com.timingjeju.api.application.trip.TripExpectedRevision;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ScheduleMutationRecord(
    UUID ownerId,
    UUID tripId,
    TripExpectedRevision expectedTrip,
    CreateScheduleItemCommand command,
    Instant transactionTime) {
  public ScheduleMutationRecord {
    Objects.requireNonNull(ownerId);
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(expectedTrip);
    Objects.requireNonNull(command);
    Objects.requireNonNull(transactionTime);
  }
}
