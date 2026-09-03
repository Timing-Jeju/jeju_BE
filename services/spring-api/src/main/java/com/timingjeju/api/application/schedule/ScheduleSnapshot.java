package com.timingjeju.api.application.schedule;

import java.util.List;
import java.util.UUID;

public record ScheduleSnapshot(
    UUID tripId, ScheduleVersionSnapshot scheduleVersion, List<ScheduleDaySnapshot> days) {
  public ScheduleSnapshot {
    days = List.copyOf(days);
  }
}
