package com.timingjeju.api.application.schedule.service;

import com.timingjeju.api.application.schedule.CreateScheduleItemCommand;
import com.timingjeju.api.application.schedule.DeleteScheduleItemCommand;
import com.timingjeju.api.application.schedule.MoveScheduleItemCommand;
import com.timingjeju.api.application.schedule.PatchScheduleItemCommand;
import com.timingjeju.api.application.schedule.ReorderScheduleCommand;
import com.timingjeju.api.application.schedule.ScheduleEditRecord;
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

  public ScheduleMutationResult patchItem(
      CurrentUser user,
      UUID tripId,
      UUID itemId,
      TripExpectedRevision expectedTrip,
      PatchScheduleItemCommand command) {
    Objects.requireNonNull(user);
    return store.patchItem(
        new ScheduleEditRecord<>(
            user.userId(), tripId, itemId, expectedTrip, command, clock.instant()));
  }

  public ScheduleMutationResult deleteItem(
      CurrentUser user,
      UUID tripId,
      UUID itemId,
      TripExpectedRevision expectedTrip,
      DeleteScheduleItemCommand command) {
    Objects.requireNonNull(user);
    return store.deleteItem(
        new ScheduleEditRecord<>(
            user.userId(), tripId, itemId, expectedTrip, command, clock.instant()));
  }

  public ScheduleMutationResult reorder(
      CurrentUser user,
      UUID tripId,
      TripExpectedRevision expectedTrip,
      ReorderScheduleCommand command) {
    Objects.requireNonNull(user);
    return store.reorder(
        new ScheduleEditRecord<>(
            user.userId(), tripId, null, expectedTrip, command, clock.instant()));
  }

  public ScheduleMutationResult moveItem(
      CurrentUser user,
      UUID tripId,
      UUID itemId,
      TripExpectedRevision expectedTrip,
      MoveScheduleItemCommand command) {
    Objects.requireNonNull(user);
    return store.moveItem(
        new ScheduleEditRecord<>(
            user.userId(), tripId, itemId, expectedTrip, command, clock.instant()));
  }
}
