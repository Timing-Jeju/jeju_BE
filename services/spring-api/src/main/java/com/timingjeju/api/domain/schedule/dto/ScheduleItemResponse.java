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
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "0",
            maximum = "1440",
            description = "일반 방문은 1분 이상이며 day_start/day_end 기준점만 0분입니다.")
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
        ItemProgressResponse progress,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"string", "null"},
            allowableValues = {"day_start", "day_end"},
            description = "일반 항목은 null입니다. 기준점은 위치와 시간을 보존하며 직접 편집하지 않습니다.")
        String boundaryRole) {
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
        ItemProgressResponse.from(item.progress()),
        item.boundaryRole());
  }
}
