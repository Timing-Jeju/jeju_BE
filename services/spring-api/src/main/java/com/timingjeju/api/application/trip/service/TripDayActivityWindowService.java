package com.timingjeju.api.application.trip.service;

import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.trip.ReplaceTripDayActivityWindowsCommand;
import com.timingjeju.api.application.trip.TripAggregate;
import com.timingjeju.api.application.trip.TripDayActivityWindowStore;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class TripDayActivityWindowService {
  private final TripDayActivityWindowStore store;
  private final Clock clock;

  public TripDayActivityWindowService(TripDayActivityWindowStore store, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
  }

  public TripAggregate replace(
      CurrentUser user, UUID tripId, long revision, ReplaceTripDayActivityWindowsCommand command) {
    return store.replace(user.userId(), tripId, revision, command, clock.instant());
  }
}
