package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.util.UUID;

public interface TripAggregateMutationCoordinator {
  <T> TripAggregateMutationCommit<T> execute(
      UUID ownerId,
      UUID tripId,
      long expectedRevision,
      Instant updatedAt,
      TripAggregateMutationOperation<T> operation);
}
