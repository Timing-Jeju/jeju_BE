package com.timingjeju.api.domain.schedule.dto;

import com.timingjeju.api.application.schedule.PatchScheduleItemCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;

@Schema(
    name = "PatchScheduleItemRequest",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PatchScheduleItemRequest(
    UUID expectedActiveScheduleVersionId,
    UUID placeId,
    UUID accommodationId,
    UUID transportEventId,
    String title,
    String plannedStartAt,
    Integer stayMinutes,
    Integer bufferAfterMinutes,
    Boolean required,
    String memo) {
  public PatchScheduleItemCommand toCommand(Set<String> presentFields) {
    try {
      return new PatchScheduleItemCommand(
          expectedActiveScheduleVersionId,
          presentFields,
          placeId,
          accommodationId,
          transportEventId,
          title,
          plannedStartAt == null ? null : OffsetDateTime.parse(plannedStartAt),
          stayMinutes,
          bufferAfterMinutes,
          required,
          memo);
    } catch (DateTimeParseException failure) {
      throw com.timingjeju.api.application.schedule.ScheduleException.itemInvalid();
    }
  }
}
