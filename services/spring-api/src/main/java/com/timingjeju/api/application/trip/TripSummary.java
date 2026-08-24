package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TripSummary(
    UUID tripId,
    String title,
    String status,
    LocalDate startDate,
    LocalDate endDate,
    String timezone,
    UUID activeScheduleVersionId,
    Integer totalScore,
    TripScoreProvenance scoreProvenance,
    Instant createdAt,
    Instant updatedAt) {}
