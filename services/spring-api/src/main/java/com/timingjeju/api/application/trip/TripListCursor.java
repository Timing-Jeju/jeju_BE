package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.util.UUID;

public record TripListCursor(Instant updatedAt, UUID tripId) {}
