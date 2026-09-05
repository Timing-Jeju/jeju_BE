package com.timingjeju.api.application.trip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record ReplaceTripPreferencesCommand(
    List<String> preferredCategories,
    String arrivalRegionCode,
    String departureRegionCode,
    List<String> preferredRegionCodes,
    UUID startPlaceId,
    UUID endPlaceId,
    List<TripTransportMode> transportModes) {
  public ReplaceTripPreferencesCommand {
    preferredCategories = immutableCopy(preferredCategories);
    preferredRegionCodes = immutableCopy(preferredRegionCodes);
    transportModes = immutableCopy(transportModes);
  }

  private static <T> List<T> immutableCopy(List<T> values) {
    return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
  }
}
