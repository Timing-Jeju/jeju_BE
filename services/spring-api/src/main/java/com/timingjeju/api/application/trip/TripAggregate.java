package com.timingjeju.api.application.trip;

import com.timingjeju.api.application.accommodation.Accommodation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TripAggregate(
    UUID tripId,
    long revision,
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
    Instant updatedAt,
    TripTransportEvents transportEvents,
    List<Accommodation> accommodations) {
  public TripAggregate {
    if (revision < 1) {
      throw new IllegalArgumentException("revision은 양수여야 합니다.");
    }
    transportModes = List.copyOf(transportModes);
    days = List.copyOf(days);
    java.util.Objects.requireNonNull(transportEvents);
    accommodations = List.copyOf(accommodations);
  }

  public TripAggregate(
      UUID tripId,
      long revision,
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
    this(
        tripId,
        revision,
        title,
        status,
        startDate,
        endDate,
        timezone,
        userPace,
        transportModes,
        days,
        activeScheduleVersionId,
        totalScore,
        scoreProvenance,
        createdAt,
        updatedAt,
        TripTransportEvents.empty(),
        List.of());
  }
}
