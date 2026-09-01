package com.timingjeju.api.application.schedule;

import java.time.Instant;
import java.util.UUID;

public record ScheduleLegSnapshot(
    UUID legId,
    int sequenceNo,
    UUID fromItemId,
    UUID toItemId,
    String transportMode,
    Instant plannedDepartureAt,
    Instant plannedArrivalAt,
    int walkMinutes,
    int waitMinutes,
    int rideMinutes,
    int transferMinutes,
    int durationMinutes,
    int bufferMinutes,
    Integer distanceMeters,
    Integer estimatedFareKrw,
    Integer riskScore) {}
