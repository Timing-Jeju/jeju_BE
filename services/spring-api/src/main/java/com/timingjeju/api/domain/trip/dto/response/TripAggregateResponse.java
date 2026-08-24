package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripAggregate;
import com.timingjeju.api.application.trip.TripDay;
import com.timingjeju.api.application.trip.TripTransportMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TripAggregateResponse(
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
    TripScoreProvenanceResponse scoreProvenance,
    String scheduleEffect,
    boolean regenerationRequired,
    Instant createdAt,
    Instant updatedAt) {
  public static TripAggregateResponse from(TripAggregate trip) {
    return new TripAggregateResponse(
        trip.tripId(),
        trip.title(),
        trip.status(),
        trip.startDate(),
        trip.endDate(),
        trip.timezone(),
        trip.userPace(),
        trip.transportModes(),
        trip.days(),
        trip.activeScheduleVersionId(),
        trip.totalScore(),
        TripScoreProvenanceResponse.from(trip.scoreProvenance()),
        "none",
        false,
        trip.createdAt(),
        trip.updatedAt());
  }
}
