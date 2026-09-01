package com.timingjeju.api.application.transportevent;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PutTransportEventCommand(
    String eventType,
    String transportType,
    UUID terminalPlaceId,
    String customTerminalName,
    OffsetDateTime scheduledAt,
    String transportNumber,
    String note) {}
