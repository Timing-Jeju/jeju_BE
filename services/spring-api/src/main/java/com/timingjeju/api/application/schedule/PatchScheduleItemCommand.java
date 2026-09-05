package com.timingjeju.api.application.schedule;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

public record PatchScheduleItemCommand(
    UUID expectedActiveScheduleVersionId,
    Set<String> presentFields,
    UUID placeId,
    UUID accommodationId,
    UUID transportEventId,
    String title,
    OffsetDateTime plannedStartAt,
    Integer stayMinutes,
    Integer bufferAfterMinutes,
    Boolean required,
    String memo) {
  private static final Set<String> EDITABLE =
      Set.of(
          "placeId",
          "accommodationId",
          "transportEventId",
          "title",
          "plannedStartAt",
          "stayMinutes",
          "bufferAfterMinutes",
          "required",
          "memo");

  public PatchScheduleItemCommand {
    presentFields = Set.copyOf(presentFields);
    if (expectedActiveScheduleVersionId == null
        || presentFields.stream().noneMatch(EDITABLE::contains)
        || presentFields.stream().anyMatch(name -> !EDITABLE.contains(name))
        || (presentFields.contains("plannedStartAt")
            && (plannedStartAt == null
                || !plannedStartAt.getOffset().equals(ZoneOffset.ofHours(9))))
        || (presentFields.contains("stayMinutes")
            && (stayMinutes == null || stayMinutes < 1 || stayMinutes > 1440))
        || (presentFields.contains("bufferAfterMinutes")
            && (bufferAfterMinutes == null || bufferAfterMinutes < 0 || bufferAfterMinutes > 1440))
        || (presentFields.contains("required") && required == null)
        || (presentFields.contains("title")
            && (title == null || title.isBlank() || title.length() > 200))
        || (presentFields.contains("memo") && memo != null && memo.length() > 500)) {
      throw ScheduleException.itemInvalid();
    }
  }

  public boolean changes(String field) {
    return presentFields.contains(field);
  }
}
