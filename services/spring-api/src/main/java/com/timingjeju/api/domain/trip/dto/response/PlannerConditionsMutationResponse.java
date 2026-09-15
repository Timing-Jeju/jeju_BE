package com.timingjeju.api.domain.trip.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PlannerConditionsMutationResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID tripId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        TripPlannerConditionsResponse plannerConditions,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"none", "maintained", "invalidated"})
        String scheduleEffect,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean regenerationRequired,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            types = {"string", "null"},
            format = "uuid")
        UUID activeScheduleVersionId) {}
