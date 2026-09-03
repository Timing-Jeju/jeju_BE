package com.timingjeju.api.application.schedule.service;

import com.timingjeju.api.application.schedule.ScheduleException;
import com.timingjeju.api.application.schedule.ScheduleLookup;
import com.timingjeju.api.application.schedule.ScheduleSnapshot;
import com.timingjeju.api.application.schedule.ScheduleStore;
import com.timingjeju.api.application.security.CurrentUser;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class ScheduleQueryService {
  private final ScheduleStore schedules;
  private final Clock clock;

  public ScheduleQueryService(ScheduleStore schedules, Clock clock) {
    this.schedules = Objects.requireNonNull(schedules);
    this.clock = Objects.requireNonNull(clock);
  }

  public ScheduleSnapshot read(CurrentUser user, UUID tripId, UUID versionId) {
    Objects.requireNonNull(user);
    Objects.requireNonNull(tripId);
    ScheduleLookup lookup = schedules.readOwned(user.userId(), tripId, versionId, clock.instant());
    if (lookup.status() == ScheduleLookup.Status.FOUND) {
      return lookup.schedule();
    }
    if (lookup.status() == ScheduleLookup.Status.TRIP_NOT_FOUND) {
      throw ScheduleException.tripNotFound();
    }
    throw ScheduleException.versionNotFound();
  }
}
