package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.util.UUID;

public interface TripPlannerConditionsStore {
  TripAggregateMutationCommit<TripPlannerConditions> replace(
      UUID ownerId, UUID tripId, long revision, TripPlannerConditions conditions, Instant now);
}
