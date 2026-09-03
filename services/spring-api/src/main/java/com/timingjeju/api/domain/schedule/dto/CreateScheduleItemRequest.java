package com.timingjeju.api.domain.schedule.dto;

import com.timingjeju.api.application.schedule.CreateScheduleItemCommand;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

public record CreateScheduleItemRequest(
    UUID expectedActiveScheduleVersionId,
    Integer dayNo,
    Integer sequenceNo,
    String itemType,
    UUID placeId,
    UUID accommodationId,
    UUID transportEventId,
    String title,
    String plannedStartAt,
    Integer stayMinutes,
    Integer bufferAfterMinutes,
    Boolean required,
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
