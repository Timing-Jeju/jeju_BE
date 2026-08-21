package com.timingjeju.api.domain.places.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Operations", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PlaceOperations(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maxLength = 1000)
        String operatingHoursText,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maxLength = 1000)
        String closedDaysText,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maxLength = 1000)
        String parkingText,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maxLength = 1000)
        String admissionFeeText) {}
