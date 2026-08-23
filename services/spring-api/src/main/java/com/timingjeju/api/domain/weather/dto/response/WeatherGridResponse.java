package com.timingjeju.api.domain.weather.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "WeatherGrid", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record WeatherGridResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "149") int nx,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "253") int ny,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            minLength = 1,
            maxLength = 100)
        String regionName) {}
