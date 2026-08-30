package com.timingjeju.api.application.trip;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateTripRecord(
    UUID ownerId,
    UUID tripId,
    String publicToken,
    CreateTripCommand command,
    List<UUID> dayIds,
    Instant createdAt) {
  public CreateTripRecord {
    dayIds = List.copyOf(dayIds);
  }
}
