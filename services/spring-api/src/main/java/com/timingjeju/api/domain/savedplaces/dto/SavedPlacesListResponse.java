package com.timingjeju.api.domain.savedplaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SavedPlacesListResponse(List<SavedPlaceResponse> items, CursorPage page) {
  @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record CursorPage(int size, boolean hasNext, String nextCursor) {}
}
