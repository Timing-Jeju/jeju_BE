package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TripSummaryResponse(
    UUID tripId,
    String title,
    String status,
    LocalDate startDate,
    LocalDate endDate,
    String timezone,
    UUID activeScheduleVersionId,
    Integer totalScore,
    TripScoreProvenanceResponse scoreProvenance,
    Instant createdAt,
    Instant updatedAt) {
  static TripSummaryResponse from(TripSummary trip) {
    return new TripSummaryResponse(
        trip.tripId(),
        trip.title(),
        trip.status(),
        trip.startDate(),
        trip.endDate(),
        trip.timezone(),
        trip.activeScheduleVersionId(),
        trip.totalScore(),
        TripScoreProvenanceResponse.from(trip.scoreProvenance()),
        trip.createdAt(),
        trip.updatedAt());
  }
}
