package com.timingjeju.api.domain.schedule.dto;

import com.timingjeju.api.application.schedule.ScheduleMutationResult;
import com.timingjeju.api.application.trip.TripEntityTag;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Schema(
    name = "ScheduleMutationResponse",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ScheduleMutationResponse(
    UUID tripId,
    UUID previousScheduleVersionId,
    UUID activeScheduleVersionId,
    int versionNo,
    String sourceType,
    boolean feasibilityStale,
    List<UUID> changedItemIds,
    String etag,
    String updatedAt) {
  private static final ZoneId JEJU = ZoneId.of("Asia/Seoul");

  public static ScheduleMutationResponse from(ScheduleMutationResult result) {
    return new ScheduleMutationResponse(
        result.tripId(),
        result.previousScheduleVersionId(),
        result.activeScheduleVersionId(),
        result.versionNo(),
        "user_edit",
        true,
        result.changedItemIds(),
        TripEntityTag.strong(result.tripId(), result.tripRevision()),
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(result.updatedAt().atZone(JEJU)));
  }
}
