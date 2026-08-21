package com.timingjeju.api.domain.places.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SavedPlaceState(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean value,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String memo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> tags) {
  public SavedPlaceState {
    tags = List.copyOf(tags);
  }
}
