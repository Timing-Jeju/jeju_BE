package com.timingjeju.api.application.transportevent;

import com.timingjeju.api.application.trip.TripExpectedRevision;
import java.time.Instant;
import java.util.UUID;

public record TransportEventDeleteRecord(
    UUID ownerId, UUID tripId, String eventType, TripExpectedRevision expected, Instant now) {}
