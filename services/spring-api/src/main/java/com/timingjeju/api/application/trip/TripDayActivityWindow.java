package com.timingjeju.api.application.trip;

import java.time.LocalTime;
import java.util.UUID;

public record TripDayActivityWindow(UUID dayId, LocalTime startTime, LocalTime endTime) {
  public TripDayActivityWindow {
    if (dayId == null
        || startTime == null
        || endTime == null
        || !startTime.isBefore(endTime)
        || startTime.getSecond() != 0
        || startTime.getNano() != 0
        || endTime.getSecond() != 0
        || endTime.getNano() != 0) {
      throw TripException.constraintViolation();
    }
  }
}
