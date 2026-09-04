package com.timingjeju.api.domain.schedule.dto;

import com.timingjeju.api.application.schedule.ScheduleMutationResult;
import com.timingjeju.api.application.trip.TripEntityTag;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Schema(
    name = "ScheduleMutationResponse",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ScheduleMutationResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID tripId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
        UUID previousScheduleVersionId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
        UUID activeScheduleVersionId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int versionNo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "user_edit")
        String sourceType,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "true")
        boolean feasibilityStale,
    @ArraySchema(
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
            uniqueItems = true,
            schema = @Schema(type = "string", format = "uuid"))
        List<UUID> changedItemIds,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            pattern =
                "^\\\"trip-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-r[1-9][0-9]*\\\"$")
        String etag,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") String updatedAt) {
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
