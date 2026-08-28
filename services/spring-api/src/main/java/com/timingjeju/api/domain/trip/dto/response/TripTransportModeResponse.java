package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripTransportMode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "TransportMode", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TripTransportModeResponse(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"public_transit", "rental_car", "taxi"})
        String mode,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "3") int priority,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean primary) {
  static TripTransportModeResponse from(TripTransportMode mode) {
    return new TripTransportModeResponse(mode.mode(), mode.priority(), mode.primary());
  }
}
