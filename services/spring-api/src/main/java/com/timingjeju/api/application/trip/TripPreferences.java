package com.timingjeju.api.application.trip;

import java.util.List;
import java.util.UUID;

public record TripPreferences(
    List<String> preferredCategories,
    String arrivalRegionCode,
    String departureRegionCode,
    List<String> preferredRegionCodes,
    UUID startPlaceId,
    UUID endPlaceId,
    List<TripTransportMode> transportModes) {
  public TripPreferences {
    preferredCategories = List.copyOf(preferredCategories);
    preferredRegionCodes = List.copyOf(preferredRegionCodes);
    transportModes = List.copyOf(transportModes);
  }
}
