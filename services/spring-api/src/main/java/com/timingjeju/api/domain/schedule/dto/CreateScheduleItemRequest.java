package com.timingjeju.api.domain.schedule.dto;

import com.timingjeju.api.application.schedule.CreateScheduleItemCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Schema(
    name = "CreateScheduleItemRequest",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateScheduleItemRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
        UUID expectedActiveScheduleVersionId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") Integer dayNo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") Integer sequenceNo,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {
              "place_visit",
              "meal",
              "accommodation",
              "arrival",
              "departure",
              "free_time",
              "custom"
            })
        String itemType,
    @Schema(format = "uuid") UUID placeId,
    @Schema(format = "uuid") UUID accommodationId,
    @Schema(format = "uuid") UUID transportEventId,
    @Schema(minLength = 1, maxLength = 200) String title,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time")
        String plannedStartAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1440")
        Integer stayMinutes,
    @Schema(minimum = "0", maximum = "1440") Integer bufferAfterMinutes,
    Boolean required,
    @Schema(
            nullable = true,
            types = {"string", "null"},
            maxLength = 500)
        String memo) {

  public CreateScheduleItemCommand toCommand() {
    try {
      return new CreateScheduleItemCommand(
          expectedActiveScheduleVersionId,
          dayNo == null ? 0 : dayNo,
          sequenceNo == null ? 0 : sequenceNo,
          itemType,
          placeId,
          accommodationId,
          transportEventId,
          title,
          plannedStartAt == null ? null : OffsetDateTime.parse(plannedStartAt),
          stayMinutes == null ? 0 : stayMinutes,
          bufferAfterMinutes == null ? 0 : bufferAfterMinutes,
          required != null && required,
          memo);
    } catch (DateTimeParseException failure) {
      throw com.timingjeju.api.application.schedule.ScheduleException.itemInvalid();
    }
  }
}
