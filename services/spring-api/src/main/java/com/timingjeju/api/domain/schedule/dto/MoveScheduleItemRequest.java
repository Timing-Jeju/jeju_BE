package com.timingjeju.api.domain.schedule.dto;

import com.timingjeju.api.application.schedule.MoveScheduleItemCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Schema(
    name = "MoveScheduleItemRequest",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record MoveScheduleItemRequest(
    UUID expectedActiveScheduleVersionId,
    Integer targetDayNo,
    Integer targetSequenceNo,
    String plannedStartAt) {
  public MoveScheduleItemCommand toCommand() {
    try {
      return new MoveScheduleItemCommand(
          expectedActiveScheduleVersionId,
          targetDayNo == null ? 0 : targetDayNo,
          targetSequenceNo == null ? 0 : targetSequenceNo,
          plannedStartAt == null ? null : OffsetDateTime.parse(plannedStartAt));
    } catch (DateTimeParseException failure) {
      throw com.timingjeju.api.application.schedule.ScheduleException.itemInvalid();
    }
  }
}
