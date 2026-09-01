package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TripPlacePreferencesMutation(
    UUID tripId,
    String scheduleEffect,
    boolean regenerationRequired,
    UUID activeScheduleVersionId,
    String tripStatus,
    Instant updatedAt,
    List<TripPlacePreference> preferences) {
  public TripPlacePreferencesMutation {
    preferences = List.copyOf(preferences);
  }
}
