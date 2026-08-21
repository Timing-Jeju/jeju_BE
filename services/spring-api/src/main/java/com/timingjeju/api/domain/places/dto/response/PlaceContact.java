package com.timingjeju.api.domain.places.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;

@Schema(name = "Contact", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PlaceContact(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String phone,
    @Schema(format = "uri", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        URI homepageUrl) {}
