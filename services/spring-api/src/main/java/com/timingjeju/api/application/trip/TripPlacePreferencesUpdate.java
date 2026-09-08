package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TripPlacePreferencesUpdate(
    UUID ownerId,
    UUID tripId,
    String expectedEtag,
    List<TripPlacePreference> preferences,
    Instant updatedAt) {
  public TripPlacePreferencesUpdate {
    preferences = List.copyOf(preferences);
  }
}
