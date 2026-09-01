package com.timingjeju.api.application.schedule;

import java.time.Instant;
import java.util.UUID;

public interface ScheduleStore {
  ScheduleLookup readOwned(UUID ownerId, UUID tripId, UUID versionId, Instant responseTime);
}
