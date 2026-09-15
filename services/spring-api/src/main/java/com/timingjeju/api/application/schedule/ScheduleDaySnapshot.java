package com.timingjeju.api.application.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ScheduleDaySnapshot(
    UUID dayId,
    int dayNo,
    LocalDate date,
    List<ScheduleItemSnapshot> items,
    List<ScheduleLegSnapshot> legs,
    boolean hasGenerationResult) {
  public ScheduleDaySnapshot(
      UUID dayId,
      int dayNo,
      LocalDate date,
      List<ScheduleItemSnapshot> items,
      List<ScheduleLegSnapshot> legs) {
    this(dayId, dayNo, date, items, legs, false);
  }

  public ScheduleDaySnapshot {
    items = List.copyOf(items);
    legs = List.copyOf(legs);
  }
}
