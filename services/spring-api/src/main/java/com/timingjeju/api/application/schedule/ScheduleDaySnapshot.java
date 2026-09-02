package com.timingjeju.api.application.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ScheduleDaySnapshot(
    UUID dayId,
    int dayNo,
    LocalDate date,
    List<ScheduleItemSnapshot> items,
    List<ScheduleLegSnapshot> legs) {
  public ScheduleDaySnapshot {
    items = List.copyOf(items);
    legs = List.copyOf(legs);
  }
}
