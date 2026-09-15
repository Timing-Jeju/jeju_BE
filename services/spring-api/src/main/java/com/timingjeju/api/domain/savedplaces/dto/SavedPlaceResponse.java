package com.timingjeju.api.domain.savedplaces.dto;

import com.timingjeju.api.domain.savedplaces.model.SavedPlace;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SavedPlaceResponse(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "항목의 opaque strong ETag. If-Match에 그대로 사용한다.",
            pattern = "^\"[A-Za-z0-9._:-]{1,128}\"$",
            minLength = 3,
            maxLength = 130)
        String etag,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID placeId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 200)
        String name,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            pattern = "^(?:[A-Z]{2}|content-type:[0-9]{1,10})$")
        String category,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            types = {"string", "null"},
            maxLength = 100)
        String regionLabel,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            types = {"string", "null"},
            format = "uri")
        String thumbnailUrl,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            types = {"integer", "null"},
            minimum = "0")
        Integer recommendedStayMinutes,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            types = {"string", "null"},
            minLength = 1,
            maxLength = 2000)
        String memo,
    @io.swagger.v3.oas.annotations.media.ArraySchema(
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
            maxItems = 20,
            uniqueItems = true,
            schema = @Schema(minLength = 1, maxLength = 50))
        List<String> tags,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "5") int priority,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            types = {"integer", "null"},
            minimum = "1",
            maximum = "365")
        Integer targetDay,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant savedAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant updatedAt) {

  public static SavedPlaceResponse from(SavedPlace place) {
    return new SavedPlaceResponse(
        com.timingjeju.api.domain.savedplaces.model.SavedPlaceEtag.strong(
            place.placeId(), place.updatedAt()),
        place.placeId(),
        place.name(),
        place.category(),
        place.regionLabel(),
        place.thumbnailUrl(),
        place.recommendedStayMinutes(),
        place.memo(),
        place.tags(),
        place.priority(),
        place.targetDay(),
        place.savedAt(),
        place.updatedAt());
  }
}
