package com.timingjeju.api.application.trip;

import java.util.UUID;

public record TripPlacePreference(
    UUID placeId, String type, Integer targetDayNo, int priority, Integer requestedStayMinutes) {
  public TripPlacePreference(UUID placeId, String type, Integer targetDayNo, int priority) {
    this(placeId, type, targetDayNo, priority, null);
  }
}
