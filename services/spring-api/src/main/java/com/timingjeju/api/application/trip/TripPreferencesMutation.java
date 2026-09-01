package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.util.UUID;

public record TripPreferencesMutation(
    UUID tripId,
    String scheduleEffect,
    boolean regenerationRequired,
    UUID activeScheduleVersionId,
    String tripStatus,
    Instant updatedAt,
    TripPreferences preferences) {}
