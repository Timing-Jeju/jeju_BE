package com.timingjeju.api.domain.places.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PlaceCursorPage(int size, boolean hasNext, String nextCursor) {}
