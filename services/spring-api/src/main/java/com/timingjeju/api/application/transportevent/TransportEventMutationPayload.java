package com.timingjeju.api.application.transportevent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.timingjeju.api.application.trip.TripEntityTag;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Schema(
    name = "TransportEventMutationResponse",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TransportEventMutationPayload(
    UUID tripId,
    String scheduleEffect,
    boolean regenerationRequired,
    UUID activeScheduleVersionId,
    String tripStatus,
    OffsetDateTime updatedAt,
    String eventType,
    boolean deleted,
    TransportEventPayload event,
    @JsonIgnore @Schema(hidden = true) String etag) {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  public static TransportEventMutationPayload from(TransportEventMutation mutation) {
    return new TransportEventMutationPayload(
        mutation.tripId(),
        mutation.scheduleEffect(),
        mutation.regenerationRequired(),
        mutation.activeScheduleVersionId(),
        mutation.tripStatus(),
        OffsetDateTime.ofInstant(mutation.updatedAt(), KST),
        mutation.eventType(),
        mutation.deleted(),
        mutation.event() == null ? null : TransportEventPayload.from(mutation.event()),
        TripEntityTag.strong(mutation.tripId(), mutation.revision()));
  }

  @Schema(name = "TransportEvent", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record TransportEventPayload(
      String eventType,
      String transportType,
      UUID terminalPlaceId,
      String customTerminalName,
      OffsetDateTime scheduledAt,
      String transportNumber,
      String note) {
    static TransportEventPayload from(TransportEvent event) {
      return new TransportEventPayload(
          event.eventType(),
          event.transportType(),
          event.terminalPlaceId(),
          event.customTerminalName(),
          event.scheduledAt(),
          event.transportNumber(),
          event.note());
    }
  }
}
