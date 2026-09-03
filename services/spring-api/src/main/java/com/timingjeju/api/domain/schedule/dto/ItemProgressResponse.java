package com.timingjeju.api.domain.schedule.dto;

import com.timingjeju.api.application.schedule.ItemProgressSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Schema(name = "ItemProgress", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ItemProgressResponse(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"planned", "active", "arrived", "completed", "skipped", "missed"})
        String status,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"string", "null"},
            format = "date-time")
        OffsetDateTime actualStartedAt,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"string", "null"},
            format = "date-time")
        OffsetDateTime actualArrivedAt,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            types = {"string", "null"},
            format = "date-time")
        OffsetDateTime actualCompletedAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time")
        OffsetDateTime updatedAt) {
  private static final ZoneId JEJU = ZoneId.of("Asia/Seoul");

  static ItemProgressResponse from(ItemProgressSnapshot progress) {
    if (progress == null) {
      return null;
    }
    return new ItemProgressResponse(
        progress.status(),
        atJeju(progress.actualStartedAt()),
        atJeju(progress.actualArrivedAt()),
        atJeju(progress.actualCompletedAt()),
        atJeju(progress.updatedAt()));
  }

  private static OffsetDateTime atJeju(Instant instant) {
    return instant == null ? null : instant.atZone(JEJU).toOffsetDateTime();
  }
}
