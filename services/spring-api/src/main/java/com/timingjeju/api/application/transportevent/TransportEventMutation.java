package com.timingjeju.api.application.transportevent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TransportEventMutation(
    UUID tripId,
    String eventType,
    boolean deleted,
    TransportEvent event,
    String scheduleEffect,
    boolean regenerationRequired,
    UUID activeScheduleVersionId,
    String tripStatus,
    long revision,
    Instant updatedAt) {
  public TransportEventMutation {
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(eventType);
    Objects.requireNonNull(scheduleEffect);
    Objects.requireNonNull(tripStatus);
    Objects.requireNonNull(updatedAt);
    if (!java.util.Set.of("arrival", "departure").contains(eventType)
        || deleted == (event != null)
        || !java.util.Set.of("none", "maintained", "invalidated").contains(scheduleEffect)
        || revision < 1) {
      throw new IllegalArgumentException("교통 이벤트 mutation projection이 올바르지 않습니다.");
    }
  }
}
