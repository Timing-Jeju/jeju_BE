package com.timingjeju.api.domain.savedplaces.dto;

import com.timingjeju.api.domain.savedplaces.model.SavedPlace;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SavedPlaceResponse(
    UUID placeId,
    String name,
    String category,
    String regionLabel,
    String thumbnailUrl,
    Integer recommendedStayMinutes,
    String memo,
    List<String> tags,
    int priority,
    Integer targetDay,
    Instant savedAt,
    Instant updatedAt) {

  public static SavedPlaceResponse from(SavedPlace place) {
    return new SavedPlaceResponse(
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
