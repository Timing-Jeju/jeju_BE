package com.timingjeju.api.application.trip;

import java.util.Optional;
import java.util.UUID;

public interface TripStore {
  TripAggregate create(CreateTripRecord record);

  Optional<TripAggregate> findOwned(UUID ownerId, UUID tripId, java.time.Instant responseTime);

  TripListSlice listOwned(
      UUID ownerId,
      String status,
      TripListCursor after,
      int fetchSize,
      java.time.Instant responseTime);

  TripMutationResult updateOwned(TripUpdateRecord record);

  void deleteOwned(UUID ownerId, UUID tripId);
}
