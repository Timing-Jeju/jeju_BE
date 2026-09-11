package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.util.UUID;

public interface TripDayActivityWindowStore {
  TripAggregate replace(
      UUID ownerId,
      UUID tripId,
      long expectedRevision,
      ReplaceTripDayActivityWindowsCommand command,
      Instant updatedAt);
}
