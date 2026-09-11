package com.timingjeju.api.domain.trip.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "TripDayLegacyV1", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TripDayLegacyV1Response(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID dayId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "30") int dayNo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date") LocalDate date) {}
