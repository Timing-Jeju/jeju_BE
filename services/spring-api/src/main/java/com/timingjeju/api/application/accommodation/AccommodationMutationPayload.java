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
    UUID activeScheduleVersionId,
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
      UUID accommodationId,
      UUID placeId,
      String customName,
      String name,
      java.time.LocalDate checkInDate,
      java.time.LocalDate checkOutDate,
      @JsonFormat(pattern = "HH:mm") java.time.LocalTime checkInTime,
      @JsonFormat(pattern = "HH:mm") java.time.LocalTime checkOutTime,
      int sequenceNo) {
    static AccommodationPayload from(Accommodation value) {
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
