package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.transportevent.TransportEventMutationPayload.TransportEventPayload;
import com.timingjeju.api.application.trip.TripTransportEvents;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "TripTransportEvents", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TripTransportEventsResponse(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            types = {"object", "null"})
        TransportEventPayload arrival,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            types = {"object", "null"})
        TransportEventPayload departure) {
  public static TripTransportEventsResponse from(TripTransportEvents events) {
    return new TripTransportEventsResponse(
        events.arrival() == null ? null : TransportEventPayload.from(events.arrival()),
        events.departure() == null ? null : TransportEventPayload.from(events.departure()));
  }
}
