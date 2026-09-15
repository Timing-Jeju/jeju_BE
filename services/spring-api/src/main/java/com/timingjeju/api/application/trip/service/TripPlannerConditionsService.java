package com.timingjeju.api.application.trip.service;

import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.trip.TripAggregateMutationCommit;
import com.timingjeju.api.application.trip.TripPlannerConditions;
import com.timingjeju.api.application.trip.TripPlannerConditionsStore;
import java.time.Clock;
import java.util.UUID;

public final class TripPlannerConditionsService {
  private final TripPlannerConditionsStore store;
  private final Clock clock;

  public TripPlannerConditionsService(TripPlannerConditionsStore store, Clock clock) {
    this.store = java.util.Objects.requireNonNull(store);
    this.clock = java.util.Objects.requireNonNull(clock);
  }

  public TripAggregateMutationCommit<TripPlannerConditions> replace(
      CurrentUser user, UUID tripId, long revision, TripPlannerConditions conditions) {
    return store.replace(user.userId(), tripId, revision, conditions, clock.instant());
  }
}
