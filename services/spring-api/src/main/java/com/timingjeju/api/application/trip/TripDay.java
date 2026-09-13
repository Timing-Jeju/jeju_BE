package com.timingjeju.api.application.trip;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record TripDay(
    UUID dayId, int dayNo, LocalDate date, LocalTime activityStartTime, LocalTime activityEndTime) {
  public TripDay(UUID dayId, int dayNo, LocalDate date) {
    this(dayId, dayNo, date, null, null);
  }
}
