package com.timingjeju.api.application.schedule;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

public record CreateScheduleItemCommand(
    UUID expectedActiveScheduleVersionId,
    int dayNo,
    int sequenceNo,
    String itemType,
    UUID placeId,
    UUID accommodationId,
    UUID transportEventId,
    String title,
    OffsetDateTime plannedStartAt,
    int stayMinutes,
    int bufferAfterMinutes,
    boolean required,
    String memo) {
  private static final Set<String> ITEM_TYPES =
      Set.of("place_visit", "meal", "accommodation", "arrival", "departure", "free_time", "custom");

  public CreateScheduleItemCommand {
    if (expectedActiveScheduleVersionId == null
        || dayNo < 1
        || sequenceNo < 1
        || !ITEM_TYPES.contains(itemType)
        || plannedStartAt == null
        || !plannedStartAt.getOffset().equals(java.time.ZoneOffset.ofHours(9))
        || stayMinutes < 1
        || stayMinutes > 1440
        || bufferAfterMinutes < 0
        || bufferAfterMinutes > 1440
        || (title != null && (title.isBlank() || title.length() > 200))
        || (memo != null && memo.length() > 500)
        || !hasRequiredReference(itemType, placeId, accommodationId, transportEventId, title)) {
      throw ScheduleException.itemInvalid();
    }
  }

  private static boolean hasRequiredReference(
      String itemType, UUID placeId, UUID accommodationId, UUID transportEventId, String title) {
    return switch (itemType) {
      case "place_visit" -> placeId != null;
      case "accommodation" -> accommodationId != null;
      case "arrival", "departure" -> transportEventId != null;
      default -> title != null;
    };
  }
}
