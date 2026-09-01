package com.timingjeju.api.application.trip;

import java.util.Objects;
import java.util.UUID;

public record TripExpectedRevision(UUID tripId, long revision) {
  public TripExpectedRevision {
    Objects.requireNonNull(tripId);
    if (revision < 1) {
      throw new IllegalArgumentException("revision은 양수여야 합니다.");
    }
  }
}
