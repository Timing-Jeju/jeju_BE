package com.timingjeju.api.application.schedule;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public record MoveScheduleItemCommand(
    UUID expectedActiveScheduleVersionId,
    int targetDayNo,
    int targetSequenceNo,
    OffsetDateTime plannedStartAt) {
  public MoveScheduleItemCommand {
    if (expectedActiveScheduleVersionId == null
        || targetDayNo < 1
        || targetSequenceNo < 1
        || plannedStartAt == null
        || !plannedStartAt.getOffset().equals(ZoneOffset.ofHours(9))) {
      throw ScheduleException.itemInvalid();
    }
  }
}
