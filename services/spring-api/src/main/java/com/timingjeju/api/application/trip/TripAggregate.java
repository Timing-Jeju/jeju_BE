package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TripAggregate(
    UUID tripId,
    String title,
    String status,
    LocalDate startDate,
    LocalDate endDate,
    String timezone,
    String userPace,
    List<TripTransportMode> transportModes,
    List<TripDay> days,
    UUID activeScheduleVersionId,
    Integer totalScore,
    TripScoreProvenance scoreProvenance,
    Instant createdAt,
    Instant updatedAt) {
  public TripAggregate {
    transportModes = List.copyOf(transportModes);
    days = List.copyOf(days);
  }
}
