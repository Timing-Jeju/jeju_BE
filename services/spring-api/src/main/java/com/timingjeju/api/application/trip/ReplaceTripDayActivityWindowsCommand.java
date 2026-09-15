package com.timingjeju.api.application.trip;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record ReplaceTripDayActivityWindowsCommand(List<TripDayActivityWindow> days) {
  public ReplaceTripDayActivityWindowsCommand {
    if (days == null || days.isEmpty() || days.size() > 30) {
      throw TripException.constraintViolation();
    }
    var ids = new HashSet<UUID>();
    for (var day : days) {
      if (day == null || !ids.add(day.dayId())) throw TripException.constraintViolation();
    }
    days = List.copyOf(days);
  }
}
