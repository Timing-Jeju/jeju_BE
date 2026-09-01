package com.timingjeju.api.application.transportevent;

import com.timingjeju.api.application.trip.TripExpectedRevision;
import java.time.Instant;
import java.util.UUID;

public record TransportEventUpsertRecord(
    UUID ownerId,
    UUID tripId,
    TripExpectedRevision expected,
    PutTransportEventCommand command,
    Instant now) {}
