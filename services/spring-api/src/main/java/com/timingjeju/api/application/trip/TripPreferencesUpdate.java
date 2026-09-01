package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.util.UUID;

public record TripPreferencesUpdate(
    UUID ownerId,
    UUID tripId,
    String expectedEtag,
    TripPreferences preferences,
    Instant updatedAt) {}
