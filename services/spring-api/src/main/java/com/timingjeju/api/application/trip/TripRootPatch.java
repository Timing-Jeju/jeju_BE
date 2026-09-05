package com.timingjeju.api.application.trip;

import java.time.LocalDate;

public record TripRootPatch(
    String title, LocalDate startDate, LocalDate endDate, String timezone, String userPace) {
  public static TripRootPatch unchanged() {
    return new TripRootPatch(null, null, null, null, null);
  }
}
