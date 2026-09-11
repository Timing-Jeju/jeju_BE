package com.timingjeju.api.application.accommodation;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.timingjeju.api.application.trip.TripEntityTag;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record AccommodationMutationPayload(
    UUID tripId,
    UUID accommodationId,
    AccommodationPayload accommodation,
    String scheduleEffect,
    boolean regenerationRequired,
    @Schema(types = {"string", "null"}) UUID activeScheduleVersionId,
    String tripStatus,
    String etag,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  public static AccommodationMutationPayload from(AccommodationMutation mutation) {
    Accommodation value = mutation.accommodation();
    return new AccommodationMutationPayload(
        mutation.tripId(),
        value.accommodationId(),
        AccommodationPayload.from(value),
        mutation.scheduleEffect(),
        mutation.regenerationRequired(),
        mutation.activeScheduleVersionId(),
        mutation.tripStatus(),
        TripEntityTag.strong(mutation.tripId(), mutation.revision()),
        OffsetDateTime.ofInstant(value.createdAt(), KST),
        OffsetDateTime.ofInstant(value.updatedAt(), KST));
  }

  @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record AccommodationPayload(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID accommodationId,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              types = {"string", "null"})
          UUID placeId,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              types = {"string", "null"},
              minLength = 1,
              maxLength = 100)
          String customName,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
          String name,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^\\d{4}-\\d{2}-\\d{2}$")
          java.time.LocalDate checkInDate,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^\\d{4}-\\d{2}-\\d{2}$")
          java.time.LocalDate checkOutDate,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              type = "string",
              pattern = "^(?:[01]\\d|2[0-3]):[0-5]\\d$")
          @JsonFormat(pattern = "HH:mm")
          java.time.LocalTime checkInTime,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              type = "string",
              pattern = "^(?:[01]\\d|2[0-3]):[0-5]\\d$")
          @JsonFormat(pattern = "HH:mm")
          java.time.LocalTime checkOutTime,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int sequenceNo) {
    public static AccommodationPayload from(Accommodation value) {
      return new AccommodationPayload(
          value.accommodationId(),
          value.placeId(),
          value.customName(),
          value.name(),
          value.checkInDate(),
          value.checkOutDate(),
          value.checkInTime(),
          value.checkOutTime(),
          value.sequenceNo());
    }
  }
}
