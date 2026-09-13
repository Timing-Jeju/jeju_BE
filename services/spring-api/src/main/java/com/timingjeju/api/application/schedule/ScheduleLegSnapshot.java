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
    Integer riskScore,
    String riskLevel,
    java.util.List<String> riskReasonCodes) {
  public ScheduleLegSnapshot {
    riskReasonCodes = java.util.List.copyOf(riskReasonCodes);
  }

  public ScheduleLegSnapshot(
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
      Integer riskScore) {
    this(
        legId,
        sequenceNo,
        fromItemId,
        toItemId,
        transportMode,
        plannedDepartureAt,
        plannedArrivalAt,
        walkMinutes,
        waitMinutes,
        rideMinutes,
        transferMinutes,
        durationMinutes,
        bufferMinutes,
        distanceMeters,
        estimatedFareKrw,
        riskScore,
        null,
        java.util.List.of());
  }
}
