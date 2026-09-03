package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripAggregate;
import com.timingjeju.api.application.trip.TripMutationResult;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(name = "TripDetail", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TripAggregateResponse(
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
            allowableValues = {"slow", "normal", "fast"})
        String userPace,
    @ArraySchema(
            minItems = 1,
            maxItems = 3,
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
            schema = @Schema(implementation = TripTransportModeResponse.class))
        List<TripTransportModeResponse> transportModes,
    @ArraySchema(
            minItems = 1,
            maxItems = 30,
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
            schema = @Schema(implementation = TripDayResponse.class))
        List<TripDayResponse> days,
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
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"none", "maintained", "invalidated"})
        String scheduleEffect,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean regenerationRequired,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant createdAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant updatedAt) {
  public static TripAggregateResponse from(TripAggregate trip) {
    return from(trip, "none", false);
  }

  public static TripAggregateResponse from(TripMutationResult result) {
    return from(result.trip(), result.scheduleEffect(), result.regenerationRequired());
  }

  private static TripAggregateResponse from(
      TripAggregate trip, String scheduleEffect, boolean regenerationRequired) {
    return new TripAggregateResponse(
        trip.tripId(),
        trip.title(),
        trip.status(),
        trip.startDate(),
        trip.endDate(),
        trip.timezone(),
        trip.userPace(),
        trip.transportModes().stream().map(TripTransportModeResponse::from).toList(),
        trip.days().stream().map(TripDayResponse::from).toList(),
        trip.activeScheduleVersionId(),
        trip.totalScore(),
        TripScoreProvenanceResponse.from(trip.scoreProvenance()),
        scheduleEffect,
        regenerationRequired,
        trip.createdAt(),
        trip.updatedAt());
  }
}
