package com.timingjeju.api.domain.places.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PlaceImage(
    @Schema(format = "uri", requiredMode = Schema.RequiredMode.REQUIRED) URI url,
    @Schema(format = "uri", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        URI thumbnailUrl,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String provider,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant observedAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Instant expiresAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean stale) {}
