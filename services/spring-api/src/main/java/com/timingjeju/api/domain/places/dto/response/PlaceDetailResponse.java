package com.timingjeju.api.domain.places.dto.response;

import com.timingjeju.api.domain.places.model.CanonicalPlaceCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PlaceDetailResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID placeId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String contentId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
    @Schema(
            pattern = CanonicalPlaceCategory.OPEN_API_PATTERN,
            requiredMode = Schema.RequiredMode.REQUIRED)
        String category,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String regionCode,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String regionLabel,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String address,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PlaceLocation location,
    @Schema(format = "uri", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        URI thumbnailUrl,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        Integer recommendedStayMinutes,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String recommendedStaySource,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        String recommendedStayPolicyVersion,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        Instant recommendedStayEffectiveAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        Instant recommendedStayUpdatedAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String operationsSummary,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SavedPlaceState saved,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String overview,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PlaceContact contact,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PlaceOperations operations,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PlaceImage> images,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<NearbyStop> nearbyStops) {

  public PlaceDetailResponse {
    images = List.copyOf(images);
    nearbyStops = List.copyOf(nearbyStops);
  }
}
