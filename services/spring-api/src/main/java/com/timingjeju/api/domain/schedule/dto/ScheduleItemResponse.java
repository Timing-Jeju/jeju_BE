package com.timingjeju.api.domain.schedule.dto;

import com.timingjeju.api.application.schedule.ScheduleItemSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Schema(name = "ScheduleItem", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ScheduleItemResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID itemId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int sequenceNo,
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
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"string", "null"},
            format = "uuid")
        UUID placeId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 200)
        String title,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time")
        OffsetDateTime plannedStartAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time")
        OffsetDateTime plannedEndAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1440")
        int stayMinutes,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "1440")
        int bufferAfterMinutes,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean required,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"string", "null"},
            maxLength = 500)
        String memo,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"object", "null"},
            implementation = ItemProgressResponse.class)
        ItemProgressResponse progress) {
  private static final ZoneId JEJU = ZoneId.of("Asia/Seoul");

  static ScheduleItemResponse from(ScheduleItemSnapshot item) {
    return new ScheduleItemResponse(
        item.itemId(),
        item.sequenceNo(),
        item.itemType(),
        item.placeId(),
        item.title(),
        item.plannedStartAt().atZone(JEJU).toOffsetDateTime(),
        item.plannedEndAt().atZone(JEJU).toOffsetDateTime(),
        item.stayMinutes(),
        item.bufferAfterMinutes(),
        item.required(),
        item.memo(),
        ItemProgressResponse.from(item.progress()));
  }
}
