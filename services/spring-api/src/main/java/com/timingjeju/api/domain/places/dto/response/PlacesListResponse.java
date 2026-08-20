package com.timingjeju.api.domain.places.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PlacesListResponse(List<PlaceListItem> items, PlaceCursorPage page) {
  public PlacesListResponse {
    items = List.copyOf(items);
  }
}
