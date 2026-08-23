package com.timingjeju.api.domain.places.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PlaceDataFreshness(
    String provider, Instant observedAt, Instant expiresAt, boolean stale) {}
