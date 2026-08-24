package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "TripSummary", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TripSummaryResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID tripId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        String title,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {
              "draft",
              "generating",
              "planned",
              "live",
              "completed",
              "cancelled",
              "failed"
            })
        String status,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date") LocalDate startDate,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date") LocalDate endDate,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "Asia/Seoul")
        String timezone,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"string", "null"},
            format = "uuid")
        UUID activeScheduleVersionId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"integer", "null"},
            minimum = "0",
            maximum = "100")
        Integer totalScore,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"object", "null"},
            implementation = TripScoreProvenanceResponse.class)
        TripScoreProvenanceResponse scoreProvenance,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant createdAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant updatedAt) {
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
