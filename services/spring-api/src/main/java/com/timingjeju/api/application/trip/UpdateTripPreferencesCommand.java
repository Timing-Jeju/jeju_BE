package com.timingjeju.api.application.trip;

import java.util.List;
import java.util.UUID;

public record UpdateTripPreferencesCommand(
    List<String> preferredCategories,
    String arrivalRegionCode,
    String departureRegionCode,
    List<String> preferredRegionCodes,
    UUID startPlaceId,
    UUID endPlaceId,
    List<TripTransportMode> transportModes) {}
