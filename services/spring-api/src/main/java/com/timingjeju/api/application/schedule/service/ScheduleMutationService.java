package com.timingjeju.api.application.schedule.service;

import com.timingjeju.api.application.schedule.CreateScheduleItemCommand;
import com.timingjeju.api.application.schedule.ScheduleMutationRecord;
import com.timingjeju.api.application.schedule.ScheduleMutationResult;
import com.timingjeju.api.application.schedule.ScheduleMutationStore;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.trip.TripExpectedRevision;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class ScheduleMutationService {
  private final ScheduleMutationStore store;
  private final Clock clock;

  public ScheduleMutationService(ScheduleMutationStore store, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
  }

  public ScheduleMutationResult addItem(
      CurrentUser user,
      UUID tripId,
      TripExpectedRevision expectedTrip,
      CreateScheduleItemCommand command) {
    Objects.requireNonNull(user);
    return store.addItem(
        new ScheduleMutationRecord(user.userId(), tripId, expectedTrip, command, clock.instant()));
  }
}
