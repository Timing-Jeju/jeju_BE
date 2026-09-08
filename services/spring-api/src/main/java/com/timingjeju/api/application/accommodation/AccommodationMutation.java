package com.timingjeju.api.application.accommodation;

import java.util.Objects;
import java.util.UUID;

public record AccommodationMutation(
    UUID tripId,
    Accommodation accommodation,
    String scheduleEffect,
    boolean regenerationRequired,
    UUID activeScheduleVersionId,
    String tripStatus,
    long revision) {
  public AccommodationMutation {
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(accommodation);
    if (!java.util.Set.of("none", "invalidated").contains(scheduleEffect)
        || !java.util.Set.of("draft", "planned").contains(tripStatus)
        || revision < 1) {
      throw new IllegalArgumentException("숙소 mutation projection이 올바르지 않습니다.");
    }
  }
}
