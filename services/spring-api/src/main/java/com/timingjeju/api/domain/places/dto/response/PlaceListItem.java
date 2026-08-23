package com.timingjeju.api.domain.places.dto.response;

import com.timingjeju.api.domain.places.model.CanonicalPlaceCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PlaceListItem(
    UUID placeId,
    String contentId,
    String name,
    @Schema(pattern = CanonicalPlaceCategory.OPEN_API_PATTERN) String category,
    String regionCode,
    String regionLabel,
    String address,
    PlaceLocation location,
    String thumbnailUrl,
    Integer recommendedStayMinutes,
    String recommendedStaySource,
    String recommendedStayPolicyVersion,
    Instant recommendedStayEffectiveAt,
    Instant recommendedStayUpdatedAt,
    String operationsSummary,
    Long distanceMeters,
    PlaceDataFreshness dataFreshness,
    boolean saved,
    String memo,
    List<String> tags) {

  public PlaceListItem {
    tags = List.copyOf(tags);
  }
}
